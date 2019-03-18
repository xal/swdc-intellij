/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.JsonObject;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SoftwareCoSessionManager {

    private static SoftwareCoSessionManager instance = null;
    public static final Logger log = Logger.getInstance("SoftwareCoSessionManager");

    public static SoftwareCoSessionManager getInstance() {
        if (instance == null) {
            instance = new SoftwareCoSessionManager();
        }
        return instance;
    }

    public static String getCodeTimeDashboardFile() {
        String dashboardFile = getSoftwareDir();
        if (SoftwareCo.isWindows()) {
            dashboardFile += "\\CodeTime.txt";
        } else {
            dashboardFile += "/CodeTime.txt";
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

    public static String getSoftwareSessionFile() {
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

    public static boolean isServerOnline() {
        SoftwareResponse resp = SoftwareCoUtils.makeApiCall("/ping", HttpGet.METHOD_NAME, null);
        boolean isOk = resp.isOk();
        SoftwareCoUtils.updateServerStatus(isOk);
        return isOk;
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
        final String dataStoreFile = getSoftwareDataStoreFile();
        File f = new File(dataStoreFile);

        if (f.exists()) {
            // found a data file, check if there's content
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
                    // we have data to send
                    String payloads = sb.toString();
                    payloads = payloads.substring(0, payloads.lastIndexOf(","));
                    payloads = "[" + payloads + "]";
                    final String batchPayload = payloads;

                    SoftwareResponse resp = SoftwareCoUtils.makeApiCall("/data/batch", HttpPost.METHOD_NAME, batchPayload);
                    if (resp.isOk() || resp.isDeactivated()) {
                        // delete the file
                        deleteFile(dataStoreFile);
                    }
                } else {
                    log.info("Code Time: No offline data to send");
                }
            } catch (Exception e) {
                log.info("Code Time: Error trying to read and send offline data.", e);
            }
        }
    }

    public static void cleanSessionInfo() {
        JsonObject jsonObj = getSoftwareSessionAsJson();
        if (jsonObj != null && jsonObj.size() > 0) {
            Set<String> keys = jsonObj.keySet();
            Set<String> keysToRemove = new HashSet<String>();
            for (String key : keys) {
                if (!key.equals("jwt") && !key.equals("name")) {
                    keysToRemove.add(key);
                }
            }
            if (keysToRemove.size() > 0) {
                for (String key : keysToRemove) {
                    jsonObj.remove(key);
                }
                String content = jsonObj.toString();
                String sessionFile = getSoftwareSessionFile();

                try {
                    Writer output = new BufferedWriter(new FileWriter(sessionFile));
                    output.write(content);
                    output.close();
                } catch (Exception e) {
                    log.info("Code Time: Failed to write cleaned up session info.", e);
                }
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

    public void deleteFile(String file) {
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
        boolean isOnline = isServerOnline();

        if (isOnline) {

            String msg = "To see your coding data in Code Time, please log in your account.";
            Project project = this.getCurrentProject();

            ApplicationManager.getApplication().invokeLater(new Runnable() {
                public void run() {
                    // ask to download the PM
                    int options = Messages.showDialog(
                            project,
                            msg,
                            "Software", new String[]{"Log in", "Not now"},
                            0, Messages.getInformationIcon());
                    if (options == 0) {
                        launchLogin();
                    }
                }
            });
        }
    }

    public static String generateToken() {
        String uuid = UUID.randomUUID().toString();
        return uuid.replace("-", "");
    }

    public void fetchDailyKpmSessionInfo() {
        String sessionsApi = "/sessions?summary=true";

        // make an async call to get the kpm info
        JsonObject jsonObj = SoftwareCoUtils.makeApiCall(sessionsApi, HttpGet.METHOD_NAME, null).getJsonObj();
        if (jsonObj != null) {

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
            String msg = "Code time: " + currentDayTimeStr;
            if (averageDailyMinutes > 0) {
                msg += " | Avg: " + averageDailyMinutesTimeStr;
            }

            SoftwareCoUtils.setStatusLineMessage(inFlowIcon, msg, "Click to see more from Code Time");

            if (SoftwareCoUtils.isCodeTimeMetricsFileOpen()) {
                SoftwareCoUtils.fetchCodeTimeMetricsContent();
            }
        }
    }

    public void statusBarClickHandler() {
        SoftwareCoUtils.launchCodeTimeMetricsDashboard();
    }

    protected static void lazilyFetchUserStatus(int retryCount) {
        SoftwareCoUtils.UserStatus userStatus = SoftwareCoUtils.getUserStatus();

        if (!userStatus.loggedIn && retryCount > 0) {
            final int newRetryCount = retryCount - 1;
            new Thread(() -> {
                try {
                    Thread.sleep(10000);
                    lazilyFetchUserStatus(newRetryCount);
                }
                catch (Exception e){
                    System.err.println(e);
                }
            }).start();
        }
    }

    public static void launchLogin() {
        String url = SoftwareCoUtils.launch_url;
        String jwt = getItem("jwt");

        url += "/onboarding?token=" + jwt;
        BrowserUtil.browse(url);

        new Thread(() -> {
            try {
                Thread.sleep(10000);
                lazilyFetchUserStatus(12);
            }
            catch (Exception e){
                System.err.println(e);
            }
        }).start();
    }

    public static void launchWebDashboard() {
        String url = SoftwareCoUtils.launch_url;
        BrowserUtil.browse(url);
    }
}
