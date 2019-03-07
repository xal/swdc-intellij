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
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
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
                    final String batchPayload = payloads;
                    ApplicationManager.getApplication().invokeLater(new Runnable() {
                        public void run() {
                            SoftwareResponse resp = SoftwareCoUtils.makeApiCall("/data/batch", HttpPost.METHOD_NAME, batchPayload);
                            if (resp.isOk() || resp.isDeactivated()) {
                                // delete the file
                                deleteFile(dataStoreFile);
                            }
                        }
                    });
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

    public static JsonObject getJsonObjectItem(String key) {
        JsonObject jsonObj = getSoftwareSessionAsJson();
        if (jsonObj != null && jsonObj.has(key) && !jsonObj.get(key).isJsonNull()) {
            return jsonObj.get(key).getAsJsonObject();
        }
        return null;
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
        SoftwareCoUtils.UserStatus userStatus = SoftwareCoUtils.getUserStatus();
        String lastUpdateTimeStr = getItem("jetbrains_lastUpdateTime");

        if (isOnline && lastUpdateTimeStr == null && !userStatus.hasUserAccounts) {
            // set the last update time so we don't try to ask too frequently
            setItem("jetbrains_lastUpdateTime", String.valueOf(System.currentTimeMillis()));

            String msg = "To see your coding data in Code Time, please log in your account.";

            final String dialogMsg = msg;

            Project project = this.getCurrentProject();

            ApplicationManager.getApplication().invokeLater(new Runnable() {
                public void run() {
                    // ask to download the PM
                    int options = Messages.showDialog(
                            project,
                            msg,
                            "Software", new String[]{"Log in", "Sign up", "Not now"},
                            0, Messages.getInformationIcon());
                    if (options == 0) {
                        launchLogin();
                    } else if (options == 1) {
                        launchSignup();
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
            SoftwareCoUtils.fetchCodeTimeMetricsContent();
        }
    }

    public void statusBarClickHandler() {
        final Project project = this.getCurrentProject();
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {
                SoftwareCoUtils.UserStatus userStatus = SoftwareCoUtils.getUserStatus();
                SoftwareCoUtils.launchCodeTimeMetricsDashboard();
            }
        });
    }

    protected static void lazilyFetchUserStatus(int retryCount) {
        SoftwareCoUtils.clearUserStatusCache();
        SoftwareCoUtils.UserStatus userStatus = SoftwareCoUtils.getUserStatus();

        if (userStatus.loggedInUser == null && retryCount > 0) {
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
        String identityId = SoftwareCoUtils.getIdentity();
        String encodedIdentityId = null;
        try {
            encodedIdentityId = URLEncoder.encode(identityId, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // url encoding failed, just use the mac addr
            encodedIdentityId = identityId;
        }

        url += "/login?addr=" + encodedIdentityId;
        BrowserUtil.browse(url);

        new Thread(() -> {
            try {
                Thread.sleep(10000);
                lazilyFetchUserStatus(4);
            }
            catch (Exception e){
                System.err.println(e);
            }
        }).start();
    }

    public static void launchSignup() {
        String url = SoftwareCoUtils.launch_url;
        String macAddress = SoftwareCoUtils.getIdentity();
        String encodedMacAddr = null;
        try {
            encodedMacAddr = URLEncoder.encode(macAddress, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // url encoding failed, just use the mac addr
            encodedMacAddr = macAddress;
        }
        url += "/onboarding?addr=" + encodedMacAddr;
        BrowserUtil.browse(url);

        new Thread(() -> {
            try {
                Thread.sleep(55000);
                lazilyFetchUserStatus(8);
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
