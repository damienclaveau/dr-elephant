/*
 * Copyright 2016 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linkedin.drelephant.mapreduce.fetchers;

import com.linkedin.drelephant.analysis.AnalyticJob;
import com.linkedin.drelephant.mapreduce.data.MapReduceApplicationData;
import com.linkedin.drelephant.mapreduce.data.MapReduceCounterData;
import com.linkedin.drelephant.mapreduce.data.MapReduceTaskData;
import com.linkedin.drelephant.math.Statistics;
import com.linkedin.drelephant.configurations.fetcher.FetcherConfigurationData;
import com.linkedin.drelephant.util.Utils;

import java.io.IOException;
import java.lang.Integer;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.v2.util.MRWebAppUtil;
import org.apache.hadoop.security.authentication.client.AuthenticatedURL;
import org.apache.hadoop.security.authentication.client.AuthenticationException;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;


/**
 * This class implements the Fetcher for MapReduce Applications on Hadoop2
 */
public class MapReduceFetcherHadoop2 extends MapReduceFetcher {
  private static final Logger logger = Logger.getLogger(MapReduceFetcherHadoop2.class);
  private static final String JOB_HISTORY_HTTP_POLICY = "mapreduce.jobhistory.http.policy";
  private static final String JOB_HISTORY_HTTP_ADDRESS = "mapreduce.jobhistory.webapp.address";
  private static final String JOB_HISTORY_HTTPS_ADDRESS = "mapreduce.jobhistory.webapp.https.address";
  // We provide one minute job fetch delay due to the job sending lag from AM/NM to JobHistoryServer HDFS

  private URLFactory _urlFactory;
  private JSONFactory _jsonFactory;
  private String _jhistoryWebAddr;

  public MapReduceFetcherHadoop2(FetcherConfigurationData fetcherConfData) throws IOException {
    super(fetcherConfData);

    final Configuration configuration = new Configuration();
    MRWebAppUtil.initialize(configuration);
    String jhistoryAddr = MRWebAppUtil.getJHSWebappURLWithScheme(configuration);

    logger.info("Connecting to the job history server at " + jhistoryAddr + "...");
    _urlFactory = new URLFactory(jhistoryAddr);
    logger.info("Connection success.");

    _jsonFactory = new JSONFactory();
    _jhistoryWebAddr = jhistoryAddr + "/jobhistory/job/";
  }

  @Override
  public MapReduceApplicationData fetchData(AnalyticJob analyticJob) throws IOException, AuthenticationException {
    String appId = analyticJob.getAppId();
    logger.debug("Fetching MapReduce ApplicationId " + appId);
    MapReduceApplicationData jobData = new MapReduceApplicationData();
    String jobId = Utils.getJobIdFromApplicationId(appId);
    jobData.setAppId(appId).setJobId(jobId);
    // Change job tracking url to job history page
    analyticJob.setTrackingUrl(_jhistoryWebAddr + jobId);
    try {

      // Fetch job config
      Properties jobConf = _jsonFactory.getProperties(_urlFactory.getJobConfigURL(jobId));
      jobData.setJobConf(jobConf);

      URL jobURL = _urlFactory.getJobURL(jobId);
      String state = _jsonFactory.getState(jobURL);

      jobData.setSubmitTime(_jsonFactory.getSubmitTime(jobURL));
      jobData.setStartTime(_jsonFactory.getStartTime(jobURL));
      jobData.setFinishTime(_jsonFactory.getFinishTime(jobURL));

      if (state.equals("SUCCEEDED")) {

        jobData.setSucceeded(true);

        // Fetch job counter
        MapReduceCounterData jobCounter = _jsonFactory.getJobCounter(_urlFactory.getJobCounterURL(jobId));

        // Fetch task data
        URL taskListURL = _urlFactory.getTaskListURL(jobId);
        List<MapReduceTaskData> mapperList = new ArrayList<MapReduceTaskData>();
        List<MapReduceTaskData> reducerList = new ArrayList<MapReduceTaskData>();
        _jsonFactory.getTaskDataAll(taskListURL, jobId, mapperList, reducerList);

        MapReduceTaskData[] mapperData = mapperList.toArray(new MapReduceTaskData[mapperList.size()]);
        MapReduceTaskData[] reducerData = reducerList.toArray(new MapReduceTaskData[reducerList.size()]);

        jobData.setCounters(jobCounter).setMapperData(mapperData).setReducerData(reducerData);
      } else if (state.equals("FAILED")) {

        jobData.setSucceeded(false);
        // Fetch job counter
        MapReduceCounterData jobCounter = _jsonFactory.getJobCounter(_urlFactory.getJobCounterURL(jobId));

        // Fetch task data
        URL taskListURL = _urlFactory.getTaskListURL(jobId);
        List<MapReduceTaskData> mapperList = new ArrayList<MapReduceTaskData>();
        List<MapReduceTaskData> reducerList = new ArrayList<MapReduceTaskData>();
        _jsonFactory.getTaskDataAll(taskListURL, jobId, mapperList, reducerList);

        MapReduceTaskData[] mapperData = mapperList.toArray(new MapReduceTaskData[mapperList.size()]);
        MapReduceTaskData[] reducerData = reducerList.toArray(new MapReduceTaskData[reducerList.size()]);

        jobData.setCounters(jobCounter).setMapperData(mapperData).setReducerData(reducerData);

        String diagnosticInfo;
        try {
          diagnosticInfo = parseException(jobData.getJobId(),  _jsonFactory.getDiagnosticInfo(jobURL));
        } catch(Exception e) {
          diagnosticInfo = null;
          logger.warn("Failed getting diagnostic info for failed job " + jobData.getJobId());
        }
        jobData.setDiagnosticInfo(diagnosticInfo);
      } else {
        // Should not reach here
        throw new RuntimeException("Job state not supported. Should be either SUCCEEDED or FAILED");
      }
    } finally {
      ThreadContextMR2.updateAuthToken();
    }

    return jobData;
  }

  private String parseException(String jobId, String diagnosticInfo) throws MalformedURLException, IOException,
                                                                            AuthenticationException {
    Matcher m = ThreadContextMR2.getDiagnosticMatcher(diagnosticInfo);
    if (m.matches()) {
      String taskId = m.group(1);
      return _jsonFactory.getTaskFailedStackTrace(_urlFactory.getTaskAllAttemptsURL(jobId, taskId));
    }
    logger.warn("Does not match regex!!");
    // Diagnostic info not present in the job. Usually due to exception during AM setup
    return "No sufficient diagnostic Info";
  }

  private URL getTaskCounterURL(String jobId, String taskId) throws MalformedURLException {
    return _urlFactory.getTaskCounterURL(jobId, taskId);
  }

  private URL getTaskAttemptURL(String jobId, String taskId, String attemptId) throws MalformedURLException {
    return _urlFactory.getTaskAttemptURL(jobId, taskId, attemptId);
  }

  private class URLFactory {

    private String _restRoot;

    private URLFactory(String hserverAddr) throws IOException {
      _restRoot = hserverAddr + "/ws/v1/history/mapreduce/jobs";
      verifyURL(_restRoot);
    }

    private void verifyURL(String url) throws IOException {
      final URLConnection connection = new URL(url).openConnection();
      // Check service availability
      connection.connect();
      return;
    }

    private URL getJobURL(String jobId) throws MalformedURLException {
      return new URL(_restRoot + "/" + jobId);
    }

    private URL getJobConfigURL(String jobId) throws MalformedURLException {
      return new URL(_restRoot + "/" + jobId + "/conf");
    }

    private URL getJobCounterURL(String jobId) throws MalformedURLException {
      return new URL(_restRoot + "/" + jobId + "/counters");
    }

    private URL getTaskListURL(String jobId) throws MalformedURLException {
      return new URL(_restRoot + "/" + jobId + "/tasks");
    }

    private URL getTaskCounterURL(String jobId, String taskId) throws MalformedURLException {
      return new URL(_restRoot + "/" + jobId + "/tasks/" + taskId + "/counters");
    }

    private URL getTaskAllAttemptsURL(String jobId, String taskId) throws MalformedURLException {
      return new URL(_restRoot + "/" + jobId + "/tasks/" + taskId + "/attempts");
    }

    private URL getTaskAttemptURL(String jobId, String taskId, String attemptId) throws MalformedURLException {
      return new URL(_restRoot + "/" + jobId + "/tasks/" + taskId + "/attempts/" + attemptId);
    }
  }

  private class JSONFactory {

    private long getStartTime(URL url) throws IOException, AuthenticationException {
      JsonNode rootNode = ThreadContextMR2.readJsonNode(url);
      return rootNode.path("job").path("startTime").getValueAsLong();
    }

    private long getFinishTime(URL url) throws IOException, AuthenticationException {
      JsonNode rootNode = ThreadContextMR2.readJsonNode(url);
      return rootNode.path("job").path("finishTime").getValueAsLong();
    }

    private long getSubmitTime(URL url) throws IOException, AuthenticationException {
      JsonNode rootNode = ThreadContextMR2.readJsonNode(url);
      return rootNode.path("job").path("submitTime").getValueAsLong();
    }

    private String getState(URL url) throws IOException, AuthenticationException {
      JsonNode rootNode = ThreadContextMR2.readJsonNode(url);
      return rootNode.path("job").path("state").getValueAsText();
    }

    private String getDiagnosticInfo(URL url) throws IOException, AuthenticationException {
      JsonNode rootNode = ThreadContextMR2.readJsonNode(url);
      String diag = rootNode.path("job").path("diagnostics").getValueAsText();
      return diag;
    }

    private Properties getProperties(URL url) throws IOException, AuthenticationException {
      Properties jobConf = new Properties();

      JsonNode rootNode = ThreadContextMR2.readJsonNode(url);
      JsonNode configs = rootNode.path("conf").path("property");

      for (JsonNode conf : configs) {
        String key = conf.get("name").getValueAsText();
        String val = conf.get("value").getValueAsText();
        jobConf.setProperty(key, val);
      }
      return jobConf;
    }

    private MapReduceCounterData getJobCounter(URL url) throws IOException, AuthenticationException {
      MapReduceCounterData holder = new MapReduceCounterData();

      JsonNode rootNode = ThreadContextMR2.readJsonNode(url);
      JsonNode groups = rootNode.path("jobCounters").path("counterGroup");

      for (JsonNode group : groups) {
        for (JsonNode counter : group.path("counter")) {
          String counterName = counter.get("name").getValueAsText();
          Long counterValue = counter.get("totalCounterValue").getLongValue();
          String groupName = group.get("counterGroupName").getValueAsText();
          holder.set(groupName, counterName, counterValue);
        }
      }
      return holder;
    }

    private MapReduceCounterData getTaskCounter(URL url) throws IOException, AuthenticationException {
      JsonNode rootNode = ThreadContextMR2.readJsonNode(url);
      JsonNode groups = rootNode.path("jobTaskCounters").path("taskCounterGroup");
      MapReduceCounterData holder = new MapReduceCounterData();

      for (JsonNode group : groups) {
        for (JsonNode counter : group.path("counter")) {
          String name = counter.get("name").getValueAsText();
          String groupName = group.get("counterGroupName").getValueAsText();
          Long value = counter.get("value").getLongValue();
          holder.set(groupName, name, value);
        }
      }
      return holder;
    }

    private long[] getTaskExecTime(URL url) throws IOException, AuthenticationException {

      JsonNode rootNode = ThreadContextMR2.readJsonNode(url);
      JsonNode taskAttempt = rootNode.path("taskAttempt");

      long startTime = taskAttempt.get("startTime").getLongValue();
      long finishTime = taskAttempt.get("finishTime").getLongValue();
      boolean isMapper = taskAttempt.get("type").getValueAsText().equals("MAP");

      long[] time;
      if (isMapper) {
        // No shuffle sore time in Mapper
        time = new long[] { finishTime - startTime, 0, 0 ,startTime, finishTime};
      } else {
        long shuffleTime = taskAttempt.get("elapsedShuffleTime").getLongValue();
        long sortTime = taskAttempt.get("elapsedMergeTime").getLongValue();
        time = new long[] { finishTime - startTime, shuffleTime, sortTime, startTime, finishTime };
      }

      return time;
    }

    private void getTaskDataAll(URL url, String jobId, List<MapReduceTaskData> mapperList,
        List<MapReduceTaskData> reducerList) throws IOException, AuthenticationException {

      JsonNode rootNode = ThreadContextMR2.readJsonNode(url);
      JsonNode tasks = rootNode.path("tasks").path("task");

      for (JsonNode task : tasks) {
        String state = task.get("state").getValueAsText();
        String taskId = task.get("id").getValueAsText();
        String attemptId = "";
        if(state.equals("SUCCEEDED")) {
           attemptId = task.get("successfulAttempt").getValueAsText();
        } else {
          JsonNode firstAttempt = getTaskFirstFailedAttempt(_urlFactory.getTaskAllAttemptsURL(jobId, taskId));
          if( firstAttempt != null) {
            attemptId = firstAttempt.get("id").getValueAsText();
          }
        }

        boolean isMapper = task.get("type").getValueAsText().equals("MAP");

        if (isMapper) {
          mapperList.add(new MapReduceTaskData(taskId, attemptId, state));
        } else {
          reducerList.add(new MapReduceTaskData(taskId, attemptId, state));
        }
      }

      getTaskData(jobId, mapperList);
      getTaskData(jobId, reducerList);
    }

    private void getTaskData(String jobId, List<MapReduceTaskData> taskList) throws IOException, AuthenticationException {

      int sampleSize = sampleAndGetSize(jobId, taskList);

      for(int i=0; i < sampleSize; i++) {
        MapReduceTaskData data = taskList.get(i);

        URL taskCounterURL = getTaskCounterURL(jobId, data.getTaskId());
        MapReduceCounterData taskCounter = getTaskCounter(taskCounterURL);

        long[] taskExecTime = null;
        if(!data.getAttemptId().isEmpty()) {
          URL taskAttemptURL = getTaskAttemptURL(jobId, data.getTaskId(), data.getAttemptId());
          taskExecTime = getTaskExecTime(taskAttemptURL);
        }
        data.setTimeAndCounter(taskExecTime, taskCounter);
      }
    }

    private String getTaskFailedStackTrace(URL taskAllAttemptsUrl) throws IOException, AuthenticationException {
      JsonNode firstAttempt = getTaskFirstFailedAttempt(taskAllAttemptsUrl);
      if(firstAttempt != null) {
        String stacktrace = firstAttempt.get("diagnostics").getValueAsText();
        return stacktrace;
      } else {
        return null;
      }
    }

    private JsonNode getTaskFirstFailedAttempt(URL taskAllAttemptsUrl) throws IOException, AuthenticationException {
      JsonNode rootNode = ThreadContextMR2.readJsonNode(taskAllAttemptsUrl);
      long firstAttemptFinishTime = Long.MAX_VALUE;
      JsonNode firstAttempt = null;
      JsonNode taskAttempts = rootNode.path("taskAttempts").path("taskAttempt");
      for (JsonNode taskAttempt : taskAttempts) {
        String state = taskAttempt.get("state").getValueAsText();
        if (state.equals("SUCCEEDED")) {
          continue;
        }
        long finishTime = taskAttempt.get("finishTime").getLongValue();
        if( finishTime < firstAttemptFinishTime) {
          firstAttempt = taskAttempt;
          firstAttemptFinishTime = finishTime;
        }
      }
      return firstAttempt;
    }
  }
}

final class ThreadContextMR2 {
  private static final Logger logger = Logger.getLogger(ThreadContextMR2.class);
  private static final AtomicInteger THREAD_ID = new AtomicInteger(1);

  private static final ThreadLocal<Integer> _LOCAL_THREAD_ID = new ThreadLocal<Integer>() {
    @Override
    public Integer initialValue() {
      return THREAD_ID.getAndIncrement();
    }
  };

  private static final ThreadLocal<Long> _LOCAL_LAST_UPDATED = new ThreadLocal<Long>();
  private static final ThreadLocal<Long> _LOCAL_UPDATE_INTERVAL = new ThreadLocal<Long>();

  private static final ThreadLocal<Pattern> _LOCAL_DIAGNOSTIC_PATTERN = new ThreadLocal<Pattern>() {
    @Override
    public Pattern initialValue() {
      // Example: "Task task_1443068695259_9143_m_000475 failed 1 times"
      return Pattern.compile(
          ".*[\\s\\u00A0]+(task_[0-9]+_[0-9]+_[m|r]_[0-9]+)[\\s\\u00A0]+.*");
    }
  };

  private static final ThreadLocal<AuthenticatedURL.Token> _LOCAL_AUTH_TOKEN =
      new ThreadLocal<AuthenticatedURL.Token>() {
        @Override
        public AuthenticatedURL.Token initialValue() {
          _LOCAL_LAST_UPDATED.set(System.currentTimeMillis());
          // Random an interval for each executor to avoid update token at the same time
          _LOCAL_UPDATE_INTERVAL.set(Statistics.MINUTE_IN_MS * 30 + new Random().nextLong()
              % (3 * Statistics.MINUTE_IN_MS));
          logger.info("Executor " + _LOCAL_THREAD_ID.get() + " update interval " + _LOCAL_UPDATE_INTERVAL.get() * 1.0
              / Statistics.MINUTE_IN_MS);
          return new AuthenticatedURL.Token();
        }
      };

  private static final ThreadLocal<AuthenticatedURL> _LOCAL_AUTH_URL = new ThreadLocal<AuthenticatedURL>() {
    @Override
    public AuthenticatedURL initialValue() {
      return new AuthenticatedURL();
    }
  };

  private static final ThreadLocal<ObjectMapper> _LOCAL_MAPPER = new ThreadLocal<ObjectMapper>() {
    @Override
    public ObjectMapper initialValue() {
      return new ObjectMapper();
    }
  };

  private ThreadContextMR2() {
    // Empty on purpose
  }

  public static Matcher getDiagnosticMatcher(String diagnosticInfo) {
    return _LOCAL_DIAGNOSTIC_PATTERN.get().matcher(diagnosticInfo);
  }

  public static JsonNode readJsonNode(URL url) throws IOException, AuthenticationException {
    HttpURLConnection conn = _LOCAL_AUTH_URL.get().openConnection(url, _LOCAL_AUTH_TOKEN.get());
    return _LOCAL_MAPPER.get().readTree(conn.getInputStream());
  }

  public static void updateAuthToken() {
    long curTime = System.currentTimeMillis();
    if (curTime - _LOCAL_LAST_UPDATED.get() > _LOCAL_UPDATE_INTERVAL.get()) {
      logger.info("Executor " + _LOCAL_THREAD_ID.get() + " updates its AuthenticatedToken.");
      _LOCAL_AUTH_TOKEN.set(new AuthenticatedURL.Token());
      _LOCAL_AUTH_URL.set(new AuthenticatedURL());
      _LOCAL_LAST_UPDATED.set(curTime);
    }
  }
}
