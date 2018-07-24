/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
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
    private boolean isAuthenticated() {
        String tokenVal = getItem("token");
        boolean isOk = true;
        if (tokenVal == null) {
            isOk = false;
        } else {
            isOk = SoftwareCoUtils.getResponseInfo(makeApiCall("/users/ping/", false, null)).isOk;
        }
        if (!isOk) {
            // update the status bar with Sign Up message
            SoftwareCoUtils.setStatusLineMessage(
                    "Software.com", "alert_light.png",
                    "", "",
                    "Click to log in to Software.com");
        }
        return isOk;
    }

    private boolean isServerOnline() {
        return SoftwareCoUtils.getResponseInfo(makeApiCall("/ping", false, null)).isOk;
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
                    if (SoftwareCoUtils.getResponseInfo(makeApiCall("/data/batch", true, payloads)).isOk) {

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

    public void checkUserAuthenticationStatus() {
        boolean isOnline = isServerOnline();
        boolean authenticated = isAuthenticated();
        boolean pastThresholdTime = isPastTimeThreshold();

        if (isOnline && !authenticated && pastThresholdTime && !confirmWindowOpen) {
            // set the last update time so we don't try to ask too frequently
            setItem("eclipse_lastUpdateTime", String.valueOf(System.currentTimeMillis()));
            confirmWindowOpen = true;

            String msg = "To see your coding data in Software.com, please log in your account.";

            final String dialogMsg = msg;

            ApplicationManager.getApplication().invokeLater(new Runnable() {
                public void run() {
                    // ask to download the PM
                    int options = Messages.showDialog(
                            msg,
                            "Software", new String[]{"Not now", "Log in"},
                            1, Messages.getInformationIcon());
                    if (options == 1) {
                        launchDashboard();
                    }
                    confirmWindowOpen = false;
                }
            });

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
            return;
        }

        JsonObject responseData = SoftwareCoUtils.getResponseInfo(
                makeApiCall("/users/plugin/confirm?token=" + tokenVal, false, null)).jsonObj;
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
        long fromSeconds = Math.round(System.currentTimeMillis() / 1000);
        // make an async call to get the kpm info
        JsonObject jsonObj = SoftwareCoUtils.getResponseInfo(
                makeApiCall("/sessions?from=" + fromSeconds +"&summary=true", false, null)).jsonObj;
        if (jsonObj != null) {
            int sessionMinAvg = 0;
            if (jsonObj.has("sessionMinAvg")) {
                sessionMinAvg = jsonObj.get("sessionMinAvg").getAsInt();
            }
            float sessionMinGoalPercent = 0f;
            String sessionTimeIcon = "";
            if (jsonObj.has("sessionMinGoalPercent")) {
                sessionMinGoalPercent = jsonObj.get("sessionMinGoalPercent").getAsFloat();
                if (sessionMinGoalPercent > 0) {
                    if (sessionMinGoalPercent < 0.45) {
                        sessionTimeIcon = "25_circle_light.png";
                    } else if (sessionMinGoalPercent < 0.70) {
                        sessionTimeIcon = "50_circle_light.png";
                    } else if (sessionMinGoalPercent < 0.95) {
                        sessionTimeIcon = "75_circle_light.png";
                    } else {
                        sessionTimeIcon = "100_circle_light.png";
                    }
                }
            }
            int avgKpm = 0;
            if (jsonObj.has("kpm")) {
                avgKpm = jsonObj.get("kpm").getAsInt();
            }
            boolean inFlow = false;
            if (jsonObj.has("inFlow")) {
                inFlow = jsonObj.get("inFlow").getAsBoolean();
            }
            long totalMin = 0;
            if (jsonObj.has("minutesTotal")) {
                totalMin = jsonObj.get("minutesTotal").getAsLong();
            }
            String sessionTime = "";
            if (totalMin == 60) {
                sessionTime = "1 hr";
            } else if (totalMin > 60) {
                sessionTime =  String.format("%.2f", (totalMin / 60)) + " hrs";
            } else if (totalMin == 1) {
                sessionTime = "1 min";
            } else {
                sessionTime = totalMin + " min";
            }

            if (avgKpm > 0 || totalMin > 0) {
                String iconName = (inFlow) ? "rocket_light.png" : "";
                String kpmStr = String.valueOf(avgKpm) + " KPM";
                SoftwareCoUtils.setStatusLineMessage(kpmStr, iconName,
                        sessionTime, sessionTimeIcon,
                        "Click to see more from Software.com");
            } else {
                SoftwareCoUtils.setStatusLineMessage(
                        "Software.com", "",
                        "", "",
                        "Click to see more from Software.com");
            }
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

        } else if (jwtToken == null || jwtToken.equals("")) {
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

    private static HttpResponse makeApiCall(String api, boolean isPost, String payload) {

        if (!SoftwareCo.TELEMTRY_ON) {
            log.info("Software.com telemetry is currently paused. Enable to view KPM metrics");
            return null;
        }

        SessionManagerHttpClient sendTask = new SessionManagerHttpClient(api, isPost, payload);

        Future<HttpResponse> response = SoftwareCoUtils.executorService.submit(sendTask);

        //
        // Handle the Future if it exist
        //
        if ( response != null ) {

            HttpResponse httpResponse = null;
            try {
                httpResponse = response.get();

                if (httpResponse != null) {
                    return httpResponse;
                }

            } catch (InterruptedException | ExecutionException e) {
                log.info("Software.com: Unable to get the response from the http request.", e);
            }
        }
        return null;
    }

    protected static class SessionManagerHttpClient implements Callable<HttpResponse> {

        private String payload = null;
        private String api = null;
        private boolean isPost = false;

        public SessionManagerHttpClient(String api, boolean isPost, String payload) {
            this.payload = payload;
            this.isPost = isPost;
            this.api = api;
        }

        @Override
        public HttpResponse call() throws Exception {
            HttpUriRequest req = null;
            try {

                HttpResponse response = null;

                if (!isPost) {
                    req = new HttpGet(SoftwareCoUtils.api_endpoint + "" + this.api);
                } else {
                    req = new HttpPost(SoftwareCoUtils.api_endpoint + "" + this.api);

                    if (payload != null) {
                        //
                        // add the json payload
                        //
                        StringEntity params = new StringEntity(payload);
                        ((HttpPost)req).setEntity(params);
                    }
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
                log.info("Software.com: Unable to make api request.", e);
            }

            return null;
        }
    }
}
