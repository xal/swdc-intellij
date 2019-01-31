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
import org.apache.http.client.methods.*;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

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

    public static String getCodeTimeDashboardFile() {
        String dashboardFile = getSoftwareDir();
        if (SoftwareCo.isWindows()) {
            dashboardFile += "\\CodeTime";
        } else {
            dashboardFile += "/CodeTime";
        }
        return dashboardFile;
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
            isOk = SoftwareCoUtils.makeApiCall("/users/ping/", HttpGet.METHOD_NAME, null).isOk();
        }
        if (!isOk) {
            // update the status bar with Sign Up message
            SoftwareCoUtils.setStatusLineMessage(
                    "warning.png", "Code Time", "Click to log in to Code Time");
        }
        return isOk;
    }

    public static boolean isDeactivated() {
        String tokenVal = getItem("token");
        if (tokenVal == null) {
            return false;
        }

        SoftwareResponse resp = SoftwareCoUtils.makeApiCall("/users/ping/", HttpGet.METHOD_NAME, null);
        if (!resp.isOk() && resp.isDeactivated()) {
            // update the status bar with Sign Up message
            SoftwareCoUtils.setStatusLineMessage(
                    "warning.png", "Code Time", "Click to log in to Code Time");
        }
        return resp.isDeactivated();
    }

    private boolean isServerOnline() {
        return SoftwareCoUtils.makeApiCall("/ping", HttpGet.METHOD_NAME, null).isOk();
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
            log.info("Code Time: Error appending to the Software data store file", e);
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
                    SoftwareResponse resp = SoftwareCoUtils.makeApiCall("/data/batch", HttpPost.METHOD_NAME, payloads);
                    if (resp.isOk() || resp.isDeactivated()) {
                        // delete the file
                        this.deleteFile(dataStoreFile);
                    }
                } else {
                    log.info("Code Time: No offline data to send");
                }
            } catch (Exception e) {
                log.info("Code Time: Error trying to read and send offline data.", e);
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
            log.info("Code Time: Failed to write the key value pair (" + key + ", " + val + ") into the session.", e);
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
                log.info("Code Time: Error trying to read and json parse the session file.", e);
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
        boolean deactivated = isDeactivated();
        boolean pastThresholdTime = isPastTimeThreshold();

        boolean requiresLogin = (isOnline && !authenticated && pastThresholdTime && !confirmWindowOpen) ? true : false;

        if (requiresLogin && project != null) {
            // set the last update time so we don't try to ask too frequently
            setItem("eclipse_lastUpdateTime", String.valueOf(System.currentTimeMillis()));
            confirmWindowOpen = true;

            String msg = "To see your coding data in Code Time, please log in your account.";

            final String dialogMsg = msg;

            ApplicationManager.getApplication().invokeLater(new Runnable() {
                public void run() {
                    // ask to download the PM
                    int options = Messages.showDialog(
                            project,
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

            new Thread(() -> {
                try {
                    if (deactivated) {
                        Thread.sleep(1000 * 60 * 60 * 24);
                    } else {
                        Thread.sleep(1000 * 25);
                    }
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
                    "warning.png", "Code Time", "Click to log in to Code Time");
            return;
        }

        JsonObject responseData = SoftwareCoUtils.makeApiCall("/users/plugin/confirm?token=" + tokenVal, HttpGet.METHOD_NAME, null).getJsonObj();
        if (responseData != null && responseData.has("jwt")) {
            // update the jwt, user and eclipse_lastUpdateTime
            setItem("jwt", responseData.get("jwt").getAsString());
            setItem("user", responseData.get("user").getAsString());
            setItem("eclipse_lastUpdateTime", String.valueOf(System.currentTimeMillis()));
        } else {
            boolean deactivated = isDeactivated();
            // check again in a couple of minutes
            new Thread(() -> {
                try {
                    if (deactivated) {
                        Thread.sleep(1000 * 60 * 60 * 24);
                    } else {
                        Thread.sleep(1000 * 120);
                    }
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
        String sessionsApi = "/sessions?summary=true";

        // make an async call to get the kpm info
        JsonObject jsonObj = SoftwareCoUtils.makeApiCall(sessionsApi, HttpGet.METHOD_NAME, null).getJsonObj();
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
            long currentDayMinutes = 0;
            if (jsonObj.has("currentDayMinutes")) {
                currentDayMinutes = jsonObj.get("currentDayMinutes").getAsLong();
            }
            long averageDailyMinutes = 0;
            if (jsonObj.has("averageDailyMinutes")) {
                averageDailyMinutes = jsonObj.get("averageDailyMinutes").getAsLong();
            }
            String sessionTimeStr = SoftwareCoUtils.humanizeMinutes(currentSessionMinutes);
            String currentDayTimeStr = SoftwareCoUtils.humanizeMinutes(currentDayMinutes);
            String averageDailyMinutesTimeStr = SoftwareCoUtils.humanizeMinutes(averageDailyMinutes);

            String inFlowIcon = currentDayMinutes > averageDailyMinutes ? "rocket.png" : null;
            String msg = "Code time today: " + currentDayTimeStr;
            if (averageDailyMinutes > 0) {
                msg += " | Avg: " + averageDailyMinutesTimeStr;
            }

            SoftwareCoUtils.setStatusLineMessage(inFlowIcon, msg, "Click to see more from Code Time");
        } else {
            if (!isDeactivated()) {
                log.info("Unable to get kpm summary");
                this.checkUserAuthenticationStatus();
                SoftwareCoUtils.setStatusLineMessage(
                        "warning.png", "Code Time", "Click to see more from Code Time");
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
}
