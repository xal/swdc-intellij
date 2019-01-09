/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.JsonObject;
import java.util.Date;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class SoftwareCoSessionManager {

    private static SoftwareCoSessionManager instance = null;
    public static final Logger log = Logger.getInstance("SoftwareCoSessionManager");

    private static long MILLIS_PER_HOUR = 1000 * 60 * 60;
    private static int LONG_THRESHOLD_HOURS = 24;

    private boolean confirmWindowOpen = false;

    private long lastTimeAuthenticated = 0;

    public static SoftwareCoSessionManager getInstance() {
        if (instance == null) {
            instance = new SoftwareCoSessionManager();
        }
        return instance;
    }

    private static String getSoftwareDir() {
        String softwareDataDir = SoftwareCo.getUserHomeDir();
        if (SoftwareCo.isWindows()) {
            softwareDataDir += "\\.software";
        } else {
            softwareDataDir += "/.software";
        }

        File f = new File(softwareDataDir);
        if (!f.exists()) {
            // make the directory
            f.mkdirs();
        }

        return softwareDataDir;
    }

    private static String getSoftwareSessionFile() {
        String file = getSoftwareDir();
        if (SoftwareCo.isWindows()) {
            file += "\\session.json";
        } else {
            file += "/session.json";
        }
        return file;
    }

    private String getSoftwareDataStoreFile() {
        String file = getSoftwareDir();
        if (SoftwareCo.isWindows()) {
            file += "\\data.json";
        } else {
            file += "/data.json";
        }
        return file;
    }

    /**
     * User session will have...
     * { user: user, jwt: jwt }
     */
    private static boolean isAuthenticated() {
        String tokenVal = getItem("token");
        boolean isOk = true;
        if (tokenVal == null) {
            isOk = false;
        } else {
            isOk = SoftwareCoUtils.getResponseInfo(makeApiCall("/users/ping/", HttpGet.METHOD_NAME, null)).isOk;
        }
        if (!isOk) {
            // update the status bar with Sign Up message
            SoftwareCoUtils.setStatusLineMessage(
                    "warning.png", "Software.com", "Click to log in to Software.com");
        }
        return isOk;
    }

    private boolean isServerOnline() {
        return SoftwareCoUtils.getResponseInfo(makeApiCall("/ping", HttpGet.METHOD_NAME, null)).isOk;
    }

    public void storePayload(String payload) {
        if (payload == null || payload.length() == 0) {
            return;
        }
        if (SoftwareCo.isWindows()) {
            payload += "\r\n";
        } else {
            payload += "\n";
        }
        String dataStoreFile = getSoftwareDataStoreFile();
        File f = new File(dataStoreFile);
        try {
            Writer output;
            output = new BufferedWriter(new FileWriter(f, true));  //clears file every time
            output.append(payload);
            output.close();
        } catch (Exception e) {
            log.info("Software.com: Error appending to the Software data store file", e);
        }
    }

    public void sendOfflineData() {
        String dataStoreFile = getSoftwareDataStoreFile();
        File f = new File(dataStoreFile);

        if (f.exists()) {
            // JsonArray jsonArray = new JsonArray();
            StringBuffer sb = new StringBuffer();
            try {
                FileInputStream fis = new FileInputStream(f);

                //Construct BufferedReader from InputStreamReader
                BufferedReader br = new BufferedReader(new InputStreamReader(fis));

                String line = null;
                while ((line = br.readLine()) != null) {
                    if (line.length() > 0) {
                        sb.append(line).append(",");
                    }
                }

                br.close();

                if (sb.length() > 0) {
                    String payloads = sb.toString();
                    payloads = payloads.substring(0, payloads.lastIndexOf(","));
                    payloads = "[" + payloads + "]";
                    if (SoftwareCoUtils.getResponseInfo(makeApiCall("/data/batch", HttpPost.METHOD_NAME, payloads)).isOk) {

                        // delete the file
                        this.deleteFile(dataStoreFile);
                    }
                } else {
                    log.info("Software.com: No offline data to send");
                }
            } catch (Exception e) {
                log.info("Software.com: Error trying to read and send offline data.", e);
            }
        }
    }

    public static void setItem(String key, String val) {
        JsonObject jsonObj = getSoftwareSessionAsJson();
        jsonObj.addProperty(key, val);

        String content = jsonObj.toString();

        String sessionFile = getSoftwareSessionFile();

        try {
            Writer output = new BufferedWriter(new FileWriter(sessionFile));
            output.write(content);
            output.close();
        } catch (Exception e) {
            log.info("Software.com: Failed to write the key value pair (" + key + ", " + val + ") into the session.", e);
        }
    }

    public static String getItem(String key) {
        JsonObject jsonObj = getSoftwareSessionAsJson();
        if (jsonObj != null && jsonObj.has(key) && !jsonObj.get(key).isJsonNull()) {
            return jsonObj.get(key).getAsString();
        }
        return null;
    }

    private static JsonObject getSoftwareSessionAsJson() {
        JsonObject data = null;

        String sessionFile = getSoftwareSessionFile();
        File f = new File(sessionFile);
        if (f.exists()) {
            try {
                byte[] encoded = Files.readAllBytes(Paths.get(sessionFile));
                String content = new String(encoded, Charset.defaultCharset());
                if (content != null) {
                    // json parse it
                    data = SoftwareCo.jsonParser.parse(content).getAsJsonObject();
                }
            } catch (Exception e) {
                log.info("Software.com: Error trying to read and json parse the session file.", e);
            }
        }
        return (data == null) ? new JsonObject() : data;
    }

    private void deleteFile(String file) {
        File f = new File(file);
        // if the file exists, delete it
        if (f.exists()) {
            f.delete();
        }
    }

    private Project getCurrentProject() {
        Project project = null;
        Editor[] editors = EditorFactory.getInstance().getAllEditors();
        if (editors != null && editors.length > 0) {
            project = editors[0].getProject();
        }
        return project;
    }

    public void checkUserAuthenticationStatus() {
        Project project = this.getCurrentProject();

        boolean isOnline = isServerOnline();
        boolean authenticated = isAuthenticated();
        boolean pastThresholdTime = isPastTimeThreshold();

        boolean requiresLogin = (isOnline && !authenticated && pastThresholdTime && !confirmWindowOpen) ? true : false;

        if (requiresLogin && project != null) {
            // set the last update time so we don't try to ask too frequently
            setItem("eclipse_lastUpdateTime", String.valueOf(System.currentTimeMillis()));
            confirmWindowOpen = true;

            String msg = "To see your coding data in Software.com, please log in your account.";

            final String dialogMsg = msg;

            Project currentProject = this.getCurrentProject();
            ApplicationManager.getApplication().invokeLater(new Runnable() {
                public void run() {
                    // ask to download the PM
                    int options = Messages.showDialog(
                            currentProject,
                            msg,
                            "Software", new String[]{"Not now", "Log in"},
                            1, Messages.getInformationIcon());
                    if (options == 1) {
                        launchDashboard();
                    }
                    confirmWindowOpen = false;
                }
            });
        } else if (requiresLogin && project == null) {
            // try again in 25 seconds
            new Thread(() -> {
                try {
                    Thread.sleep(1000 * 25);
                    checkUserAuthenticationStatus();
                }
                catch (Exception e){
                    System.err.println(e);
                }
            }).start();
        }
    }

    /**
     * Checks the last time we've updated the session info
     */
    private boolean isPastTimeThreshold() {
        String lastUpdateTimeStr = getItem("eclipse_lastUpdateTime");
        Long lastUpdateTime = (lastUpdateTimeStr != null) ? Long.valueOf(lastUpdateTimeStr) : null;
        if (lastUpdateTime != null &&
                System.currentTimeMillis() - lastUpdateTime.longValue() < MILLIS_PER_HOUR * LONG_THRESHOLD_HOURS) {
            return false;
        }
        return true;
    }

    public static void checkTokenAvailability() {
        String tokenVal = getItem("token");

        if (tokenVal == null || tokenVal.equals("")) {
            SoftwareCoUtils.setStatusLineMessage(
                    "warning.png", "Software.com", "Click to log in to Software.com");
            return;
        }

        JsonObject responseData = SoftwareCoUtils.getResponseInfo(
                makeApiCall("/users/plugin/confirm?token=" + tokenVal, HttpGet.METHOD_NAME, null)).jsonObj;
        if (responseData != null && responseData.has("jwt")) {
            // update the jwt, user and eclipse_lastUpdateTime
            setItem("jwt", responseData.get("jwt").getAsString());
            setItem("user", responseData.get("user").getAsString());
            setItem("eclipse_lastUpdateTime", String.valueOf(System.currentTimeMillis()));
        } else {
            // check again in a couple of minutes
            new Thread(() -> {
                try {
                    Thread.sleep(1000 * 120);
                    checkTokenAvailability();
                }
                catch (Exception e){
                    System.err.println(e);
                }
            }).start();
        }
    }

    public static String generateToken() {
        String uuid = UUID.randomUUID().toString();
        return uuid.replace("-", "");
    }

    public void fetchDailyKpmSessionInfo() {
        Date d = SoftwareCoUtils.atStartOfDay(new Date());
        long fromSeconds = Math.round(d.getTime() / 1000);
        String sessionsApi = "/sessions?from=" + fromSeconds +"&summary=true";

        // make an async call to get the kpm info
        JsonObject jsonObj = SoftwareCoUtils.getResponseInfo(
                makeApiCall(sessionsApi, HttpGet.METHOD_NAME, null)).jsonObj;
        if (jsonObj != null) {

            float currentSessionGoalPercent = 0f;
            String sessionTimeIcon = "";
            if (jsonObj.has("currentSessionGoalPercent")) {
                currentSessionGoalPercent = jsonObj.get("currentSessionGoalPercent").getAsFloat();
                if (currentSessionGoalPercent > 0) {
                    if (currentSessionGoalPercent < 0.35) {
                        sessionTimeIcon = "0.png"; // "🌘";
                    } else if (currentSessionGoalPercent < 0.70) {
                        sessionTimeIcon = "25.png"; // "🌗";
                    } else if (currentSessionGoalPercent < 0.93) {
                        sessionTimeIcon = "50.png"; // "🌖";
                    } else {
                        sessionTimeIcon = "100.png"; // "🌕";
                    }
                }
            }
            int lastKpm = 0;
            if (jsonObj.has("lastKpm")) {
                lastKpm = jsonObj.get("lastKpm").getAsInt();
            }
            boolean inFlow = false;
            if (jsonObj.has("inFlow")) {
                inFlow = jsonObj.get("inFlow").getAsBoolean();
            }
            long currentSessionMinutes = 0;
            if (jsonObj.has("currentSessionMinutes")) {
                currentSessionMinutes = jsonObj.get("currentSessionMinutes").getAsLong();
            }
            String sessionTimeStr = "";
            if (currentSessionMinutes == 60) {
                sessionTimeStr = "1 hr";
            } else if (currentSessionMinutes > 60) {
                float fval = (float)currentSessionMinutes / 60;
                try {
                    sessionTimeStr = String.format("%.2f", fval) + " hrs";
                } catch (Exception e) {
                    sessionTimeStr = String.valueOf(fval);
                }
            } else if (currentSessionMinutes == 1) {
                sessionTimeStr = "1 min";
            } else {
                sessionTimeStr = currentSessionMinutes + " min";
            }

            if (lastKpm > 0 || currentSessionMinutes > 0) {
                String kpmStr = String.valueOf(lastKpm) + " KPM,";
                String kpmIcon = (inFlow) ? "rocket.png" : null;
                if (kpmIcon == null) {
                    kpmStr = "<S> " + kpmStr;
                }

                SoftwareCoUtils.setStatusLineMessage(
                        kpmIcon, kpmStr, sessionTimeIcon, sessionTimeStr, "Click to see more from Software.com");
            } else {
                SoftwareCoUtils.setStatusLineMessage(
                        "Software.com", "Click to see more from Software.com");
            }
        } else {
            log.info("Unable to get kpm summary");
            this.checkUserAuthenticationStatus();
            SoftwareCoUtils.setStatusLineMessage(
                    "warning.png", "Software.com", "Click to see more from Software.com");
        }
    }

    public static void launchDashboard() {
        String url = SoftwareCoUtils.launch_url;
        String jwtToken = getItem("jwt");
        String tokenVal = getItem("token");
        boolean checkForTokenAvailability = false;

        if (tokenVal == null || tokenVal.equals("")) {
            checkForTokenAvailability = true;
            tokenVal = SoftwareCoSessionManager.generateToken();
            SoftwareCoSessionManager.setItem("token", tokenVal);
            url += "/onboarding?token=" + tokenVal;

        } else if (jwtToken == null || jwtToken.equals("") || !isAuthenticated()) {
            checkForTokenAvailability = true;
            url += "/onboarding?token=" + tokenVal;
        }

        // launch the dashboard with the possible onboarding + token
        BrowserUtil.browse(url);

        if (checkForTokenAvailability) {
            // check for the token in a minute
            new Thread(() -> {
                try {
                    Thread.sleep(1000 * 60);
                    SoftwareCoSessionManager.checkTokenAvailability();
                } catch (Exception e) {
                    System.err.println(e);
                }
            }).start();
        }
    }

    public static HttpResponse makeApiCall(String api, String httpMethodName, String payload) {

        if (!SoftwareCo.TELEMTRY_ON) {
            log.info("Software.com telemetry is currently paused. Enable to view KPM metrics");
            return null;
        }

        try {
            SessionManagerHttpClient sendTask = new SessionManagerHttpClient(api, httpMethodName, payload);

            Future<HttpResponse> response = SoftwareCoUtils.executorService.submit(sendTask);

            //
            // Handle the Future if it exist
            //
            if (response != null) {

                HttpResponse httpResponse = null;
                try {
                    httpResponse = response.get();

                    if (httpResponse != null) {
                        return httpResponse;
                    }

                } catch (InterruptedException | ExecutionException e) {
                    log.info("Software.com: Unable to get the response from the http request.", e);
                    SoftwareCoUtils.setStatusLineMessage(
                            "warning.png", "Software.com", "Click to log in to Software.com");
                }
            }
        } catch (Exception e) {
            SoftwareCoUtils.setStatusLineMessage(
                    "warning.png", "Software.com", "Click to log in to Software.com");
        }
        return null;
    }

    protected static class SessionManagerHttpClient implements Callable<HttpResponse> {

        private String payload = null;
        private String api = null;
        private String httpMethodName = HttpGet.METHOD_NAME;

        public SessionManagerHttpClient(String api, String httpMethodName, String payload) {
            this.payload = payload;
            this.httpMethodName = httpMethodName;
            this.api = api;
        }

        @Override
        public HttpResponse call() throws Exception {
            HttpUriRequest req = null;
            try {

                HttpResponse response = null;

                if (httpMethodName.equals(HttpPost.METHOD_NAME)) {
                    req = new HttpPost(SoftwareCoUtils.api_endpoint + "" + this.api);

                    if (payload != null) {
                        //
                        // add the json payload
                        //
                        StringEntity params = new StringEntity(payload);
                        ((HttpPost) req).setEntity(params);
                    }
                } else if (httpMethodName.equals(HttpDelete.METHOD_NAME)) {
                    req = new HttpDelete(SoftwareCoUtils.api_endpoint + "" + this.api);
                } else {
                    req = new HttpGet(SoftwareCoUtils.api_endpoint + "" + this.api);
                }

                String jwtToken = getItem("jwt");
                // obtain the jwt session token if we have it
                if (jwtToken != null) {
                    req.addHeader("Authorization", jwtToken);
                }

                req.addHeader("Content-type", "application/json");

                // execute the request
                SoftwareCoUtils.logApiRequest(req, payload);
                response = SoftwareCoUtils.httpClient.execute(req);

                //
                // Return the response
                //
                return response;
            } catch (Exception e) {
                log.info("Software.com: Unable to make api request. " + e.toString(), null);
            }

            return null;
        }
    }
}
