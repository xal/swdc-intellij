/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.HttpClientBuilder;

import javax.swing.*;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class SoftwareCoUtils {

    public static final Logger LOG = Logger.getLogger("SoftwareCoUtils");

    // set the api endpoint to use
    public final static String api_endpoint = "https://api.software.com";
    // set the launch url to use
    public final static String launch_url = "https://app.software.com";

    public static HttpClient httpClient;
    public static HttpClient pingClient;

    public static JsonParser jsonParser = new JsonParser();

    // sublime = 1, vs code = 2, eclipse = 3, intellij = 4, visual studio = 6, atom = 7
    public static int pluginId = 4;
    public static String version = "0.1.68";

    public final static ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();

    private final static int EOF = -1;

    private static boolean fetchingResourceInfo = false;
    private static JsonObject lastResourceInfo = new JsonObject();

    private static Long lastRegisterUserCheck = null;
    private static UserStatus currentUserStatus = null;

    private static boolean appAvailable = true;

    private static String NO_DATA = "CODE TIME\n\nNo data available\n";

    static {
        // initialize the HttpClient
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(3000)
                .setConnectionRequestTimeout(3000)
                .setSocketTimeout(3000)
                .build();

        pingClient = HttpClientBuilder.create().setDefaultRequestConfig(config).build();
        httpClient = HttpClientBuilder.create().build();
    }

    public static class UserStatus {
        public User loggedInUser;
        public String email;
        public boolean hasUserAccounts;
    }

    // "id", "email", "plugin_jwt", "mac_addr", "mac_addr_share"
    public static class User {
        public Long id;
        public String email;
        public String plugin_jwt;
        public String mac_addr;
        public String mac_addr_share;
    }

    public static void clearUserStatusCache() {
        lastRegisterUserCheck = null;
        currentUserStatus = null;
    }

    public static void updateServerStatus(boolean isOnlineStatus) {
        appAvailable = isOnlineStatus;
    }

    public static SoftwareResponse makeApiCall(String api, String httpMethodName, String payload) {
        return makeApiCall(api, httpMethodName, payload, null);
    }

    public static SoftwareResponse makeApiCall(String api, String httpMethodName, String payload, String overridingJwt) {

        SoftwareResponse softwareResponse = new SoftwareResponse();
        if (!SoftwareCo.TELEMTRY_ON) {
            softwareResponse.setIsOk(true);
            return softwareResponse;
        }

        SoftwareHttpManager httpTask = null;
        if (api.contains("/ping") || api.contains("/sessions") || api.contains("/dashboard") || api.contains("/users/plugin/accounts")) {
            // if the server is having issues, we'll timeout within 3 seconds for these calls
            httpTask = new SoftwareHttpManager(api, httpMethodName, payload, overridingJwt, pingClient);
        } else {
            if (httpMethodName.equals(HttpPost.METHOD_NAME)) {
                // continue, POSTS encapsulated "invokeLater" with a timeout of 3 seconds
                httpTask = new SoftwareHttpManager(api, httpMethodName, payload, overridingJwt, pingClient);
            } else {
                if (!appAvailable) {
                    // bail out
                    softwareResponse.setIsOk(false);
                    return softwareResponse;
                }
                httpTask = new SoftwareHttpManager(api, httpMethodName, payload, overridingJwt, httpClient);
            }
        }
        Future<HttpResponse> response = EXECUTOR_SERVICE.submit(httpTask);

        //
        // Handle the Future if it exist
        //
        if (response != null) {
            try {
                HttpResponse httpResponse = response.get();
                if (httpResponse != null) {
                    int statusCode = httpResponse.getStatusLine().getStatusCode();
                    if (statusCode < 300) {
                        softwareResponse.setIsOk(true);
                    }
                    softwareResponse.setCode(statusCode);
                    HttpEntity entity = httpResponse.getEntity();
                    JsonObject jsonObj = null;
                    if (entity != null) {
                        try {
                            ContentType contentType = ContentType.getOrDefault(entity);
                            String mimeType = contentType.getMimeType();
                            String jsonStr = getStringRepresentation(entity);
                            softwareResponse.setJsonStr(jsonStr);
                            LOG.log(Level.INFO, "Code Time: API response {0}", jsonStr);
                            if (jsonStr != null && mimeType.indexOf("text/plain") == -1) {
                                Object jsonEl = null;
                                try {
                                    jsonEl = jsonParser.parse(jsonStr);
                                } catch (Exception e) {
                                    //
                                }

                                if (jsonEl != null && jsonEl instanceof JsonElement) {
                                    try {
                                        JsonElement el = (JsonElement)jsonEl;
                                        if (el.isJsonPrimitive()) {
                                            if (statusCode < 300) {
                                                softwareResponse.setDataMessage(el.getAsString());
                                            } else {
                                                softwareResponse.setErrorMessage(el.getAsString());
                                            }
                                        } else {
                                            jsonObj = ((JsonElement) jsonEl).getAsJsonObject();
                                            softwareResponse.setJsonObj(jsonObj);
                                        }
                                    } catch (Exception e) {
                                        LOG.log(Level.WARNING, "Unable to parse response data: {0}", e.getMessage());
                                    }
                                }
                            }
                        } catch (IOException e) {
                            String errorMessage = "Code Time: Unable to get the response from the http request, error: " + e.getMessage();
                            softwareResponse.setErrorMessage(errorMessage);
                            LOG.log(Level.WARNING, errorMessage);
                        }
                    }

                    if (statusCode >= 400 && statusCode < 500 && jsonObj != null) {
                        if (jsonObj.has("code")) {
                            String code = jsonObj.get("code").getAsString();
                            if (code != null && code.equals("DEACTIVATED")) {
                                SoftwareCoUtils.setStatusLineMessage(
                                        "warning.png", "Code Time", "To see your coding data in Code Time, please reactivate your account.");
                                softwareResponse.setDeactivated(true);
                            }
                        }
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                String errorMessage = "Code Time: Unable to get the response from the http request, error: " + e.getMessage();
                softwareResponse.setErrorMessage(errorMessage);
                LOG.log(Level.WARNING, errorMessage);
            }
        }

        return softwareResponse;
    }

    private static String getStringRepresentation(HttpEntity res) throws IOException {
        if (res == null) {
            return null;
        }

        ContentType contentType = ContentType.getOrDefault(res);
        String mimeType = contentType.getMimeType();
        boolean isPlainText = mimeType.indexOf("text/plain") != -1;

        InputStream inputStream = res.getContent();

        // Timing information--- verified that the data is still streaming
        // when we are called (this interval is about 2s for a large response.)
        // So in theory we should be able to do somewhat better by interleaving
        // parsing and reading, but experiments didn't show any improvement.
        //

        StringBuffer sb = new StringBuffer();
        InputStreamReader reader;
        reader = new InputStreamReader(inputStream);

        BufferedReader br = new BufferedReader(reader);
        boolean done = false;
        while (!done) {
            String aLine = br.readLine();
            if (aLine != null) {
                sb.append(aLine);
                if (isPlainText) {
                    sb.append("\n");
                }
            } else {
                done = true;
            }
        }
        br.close();

        return sb.toString();
    }

    public static Date atStartOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private static boolean isOk(HttpResponse response) {
        return response != null && response.getStatusLine() != null && response.getStatusLine().getStatusCode() == 200;
    }

    public static void logApiRequest(HttpUriRequest req, String payload) {
        LOG.info("Code Time: executing request "
                + "[method: " + req.getMethod() + ", URI: " + req.getURI()
                + ", payload: " + payload + "]");
    }

    public static synchronized void setStatusLineMessage(
            final String singleMsg, final String tooltip) {
        setStatusLineMessage(null, singleMsg, null, null, tooltip);
    }

    public static synchronized void setStatusLineMessage(
            final String singleIcon, final String singleMsg,
            final String tooltip) {
        setStatusLineMessage(singleIcon, singleMsg, null, null, tooltip);
    }

    public static Project getOpenProject() {
        ProjectManager projMgr = ProjectManager.getInstance();
        Project[] projects = projMgr.getOpenProjects();
        if (projects != null && projects.length > 0) {
            return projects[0];
        }
        return null;
    }

    public static synchronized void setStatusLineMessage(
            final String kpmIcon, final String kpmMsg,
            final String timeIcon, final String timeMsg,
            final String tooltip) {
        try {
            Project p = getOpenProject();
            if (p == null) {
                return;
            }
            final StatusBar statusBar = WindowManager.getInstance().getStatusBar(p);

            if (statusBar != null) {
                updateStatusBar(kpmIcon, kpmMsg, timeIcon, timeMsg, tooltip);
            }
        } catch (Exception e) {
            //
        }
    }

    private static void updateStatusBar(final String kpmIcon, final String kpmMsg,
                                        final String timeIcon, final String timeMsg,
                                        final String tooltip) {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {
                ProjectManager pm = ProjectManager.getInstance();
                if (pm != null && pm.getOpenProjects() != null && pm.getOpenProjects().length > 0) {
                    try {
                        Project p = pm.getOpenProjects()[0];
                        final StatusBar statusBar = WindowManager.getInstance().getStatusBar(p);

                        if (statusBar != null) {
                            if (statusBar.getWidget(SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID + "_kpmmsg") != null) {
                                statusBar.removeWidget(SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID + "_kpmmsg");
                            }
                            if (statusBar.getWidget(SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID + "_timemsg") != null) {
                                statusBar.removeWidget(SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID + "_timemsg");
                            }
                            if (statusBar.getWidget(SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID + "_kpmicon") != null) {
                                statusBar.removeWidget(SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID + "_kpmicon");
                            }
                            if (statusBar.getWidget(SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID + "_timeicon") != null) {
                                statusBar.removeWidget(SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID + "_timeicon");
                            }

                            String id = SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID + "_kpmicon";
                            if (kpmIcon != null) {
                                SoftwareCoStatusBarKpmIconWidget kpmIconWidget = buildStatusBarIconWidget(
                                        kpmIcon, tooltip, id);
                                statusBar.addWidget(kpmIconWidget, id);
                                statusBar.updateWidget(id);
                            }

                            id = SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID + "_kpmmsg";
                            SoftwareCoStatusBarKpmTextWidget kpmWidget = buildStatusBarTextWidget(
                                    kpmMsg, tooltip, id);
                            statusBar.addWidget(kpmWidget, id);
                            statusBar.updateWidget(id);

                            id = SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID + "_timeicon";
                            if (timeIcon != null) {
                                SoftwareCoStatusBarKpmIconWidget timeIconWidget = buildStatusBarIconWidget(
                                        timeIcon, tooltip, id);
                                statusBar.addWidget(timeIconWidget, id);
                                statusBar.updateWidget(id);
                            }

                            id = SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID + "_timemsg";
                            if (timeMsg != null) {
                                SoftwareCoStatusBarKpmTextWidget timeWidget = buildStatusBarTextWidget(
                                        timeMsg, tooltip, id);
                                statusBar.addWidget(timeWidget, id);
                                statusBar.updateWidget(id);
                            }
                        }
                    } catch(Exception e){
                        //
                    }
                }
            }
        });
    }

    public static SoftwareCoStatusBarKpmTextWidget buildStatusBarTextWidget(String msg, String tooltip, String id) {
        SoftwareCoStatusBarKpmTextWidget textWidget =
                new SoftwareCoStatusBarKpmTextWidget(id);
        textWidget.setText(msg);
        textWidget.setTooltip(tooltip);
        return textWidget;
    }

    public static SoftwareCoStatusBarKpmIconWidget buildStatusBarIconWidget(String iconName, String tooltip, String id) {
        Icon icon = IconLoader.findIcon("/com/softwareco/intellij/plugin/assets/" + iconName);

        SoftwareCoStatusBarKpmIconWidget iconWidget =
                new SoftwareCoStatusBarKpmIconWidget(id);
        iconWidget.setIcon(icon);
        iconWidget.setTooltip(tooltip);
        return iconWidget;
    }

    public static String humanizeMinutes(long minutes) {
        String str = "";
        if (minutes == 60) {
            str = "1 hr";
        } else if (minutes > 60) {
            float hours = (float)minutes / 60;
            try {
                if (hours % 1 == 0) {
                    // don't return a number with 2 decimal place precision
                    str = String.format("%.0f", hours) + " hrs";
                } else {
                    // hours = Math.round(hours * 10) / 10;
                    str = String.format("%.1f", hours) + " hrs";
                }
            } catch (Exception e) {
                str = String.format("%s hrs", String.valueOf(Math.round(hours)));
            }
        } else if (minutes == 1) {
            str = "1 min";
        } else {
            str = minutes + " min";
        }
        return str;
    }

    protected static boolean isItunesRunning() {
        // get running of application "iTunes"
        String[] args = { "osascript", "-e", "get running of application \"iTunes\"" };
        String result = runCommand(args, null);
        return (result != null) ? Boolean.valueOf(result) : false;
    }

    protected static String itunesTrackScript = "tell application \"iTunes\"\n" +
            "set track_artist to artist of current track\n" +
            "set track_album to album of current track\n" +
            "set track_name to name of current track\n" +
            "set track_duration to duration of current track\n" +
            "set track_id to id of current track\n" +
            "set track_genre to genre of current track\n" +
            "set track_state to player state\n" +
            "set json to \"type='itunes';album='\" & track_album & \"';genre='\" & track_genre & \"';artist='\" & track_artist & \"';id='\" & track_id & \"';name='\" & track_name & \"';state='\" & track_state & \"';duration='\" & track_duration & \"'\"\n" +
            "end tell\n" +
            "return json\n";

    protected static String getItunesTrack() {
        String[] args = { "osascript", "-e", itunesTrackScript };
        return runCommand(args, null);
    }

    protected static boolean isSpotifyRunning() {
        String[] args = { "osascript", "-e", "get running of application \"Spotify\"" };
        String result = runCommand(args, null);
        return (result != null) ? Boolean.valueOf(result) : false;
    }

    protected static String spotifyTrackScript = "tell application \"Spotify\"\n" +
                "set track_artist to artist of current track\n" +
                "set track_album to album of current track\n" +
                "set track_name to name of current track\n" +
                "set track_duration to duration of current track\n" +
                "set track_id to id of current track\n" +
                "set track_state to player state\n" +
                "set json to \"type='spotify';album='\" & track_album & \"';genre='';artist='\" & track_artist & \"';id='\" & track_id & \"';name='\" & track_name & \"';state='\" & track_state & \"';duration='\" & track_duration & \"'\"\n" +
            "end tell\n" +
            "return json\n";

    protected static String getSpotifyTrack() {
        String[] args = { "osascript", "-e", spotifyTrackScript };
        return runCommand(args, null);
    }

    public static JsonObject getCurrentMusicTrack() {
        JsonObject jsonObj = new JsonObject();
        if (!SoftwareCo.isMac()) {
            return jsonObj;
        }

        boolean spotifyRunning = isSpotifyRunning();
        boolean itunesRunning = isItunesRunning();

        String trackInfo = "";
        // Vintage Trouble, My Whole World Stopped Without You, spotify:track:7awBL5Pu8LD6Fl7iTrJotx, My Whole World Stopped Without You, 244080
        if (spotifyRunning) {
            trackInfo = getSpotifyTrack();
        } else if (itunesRunning) {
            trackInfo = getItunesTrack();
        }

        if (trackInfo != null && !trackInfo.equals("")) {
            // trim and replace things
            trackInfo = trackInfo.trim();
            trackInfo = trackInfo.replace("\"", "");
            trackInfo = trackInfo.replace("'", "");
            String[] paramParts = trackInfo.split(";");
            for (String paramPart : paramParts) {
                paramPart = paramPart.trim();
                String[] params = paramPart.split("=");
                if (params != null && params.length == 2) {
                    jsonObj.addProperty(params[0], params[1]);
                }
            }

        }
        return jsonObj;
    }

    /**
     * Execute the args
     * @param args
     * @return
     */
    public static String runCommand(String[] args, String dir) {
        // use process builder as it allows to run the command from a specified dir
        ProcessBuilder builder = new ProcessBuilder();

        try {
            builder.command(args);
            if (dir != null) {
                // change to the directory to run the command
                builder.directory(new File(dir));
            }
            Process process = builder.start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            InputStream is = process.getInputStream();
            copyLarge(is, baos, new byte[4096]);
            return baos.toString().trim();

        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static long copyLarge(InputStream input, OutputStream output, byte[] buffer) throws IOException {

        long count = 0;
        int n;
        while (EOF != (n = input.read(buffer))) {
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }

    // get the git resource config information
    public static JsonObject getResourceInfo(String projectDir) {
        if (fetchingResourceInfo) {
            return null;
        }

        fetchingResourceInfo = true;
        lastResourceInfo = new JsonObject();

        // is the project dir avail?
        if (projectDir != null && !projectDir.equals("")) {
            try {
                String[] branchCmd = { "git", "symbolic-ref", "--short", "HEAD" };
                String branch = runCommand(branchCmd, projectDir);

                String[] identifierCmd = { "git", "config", "--get", "remote.origin.url" };
                String identifier = runCommand(identifierCmd, projectDir);

                String[] emailCmd = { "git", "config", "user.email" };
                String email = runCommand(emailCmd, projectDir);

                String[] tagCmd = { "git", "describe", "--all" };
                String tag = runCommand(tagCmd, projectDir);

                if (StringUtils.isNotBlank(branch) && StringUtils.isNotBlank(identifier)) {
                    lastResourceInfo.addProperty("identifier", identifier);
                    lastResourceInfo.addProperty("branch", branch);
                    lastResourceInfo.addProperty("email", email);
                    lastResourceInfo.addProperty("tag", tag);
                }
            } catch (Exception e) {
                //
            }
        }

        fetchingResourceInfo = false;

        return lastResourceInfo;
    }

    public static void launchSoftwareTopForty() {
        BrowserUtil.browse("http://api.software.com/music/top40");
    }

    public static void fetchCodeTimeMetricsContent() {
        Project p = getOpenProject();
        if (p == null) {
            return;
        }
        String api = "/dashboard";
        String dashboardContent = SoftwareCoUtils.makeApiCall(api, HttpGet.METHOD_NAME, null).getJsonStr();
        if (dashboardContent == null || dashboardContent.trim().isEmpty()) {
            dashboardContent = NO_DATA;
        }
        String codeTimeFile = SoftwareCoSessionManager.getCodeTimeDashboardFile();
        File f = new File(codeTimeFile);

        Writer writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(codeTimeFile), StandardCharsets.UTF_8));
            writer.write(dashboardContent);
        } catch (IOException ex) {
            // Report
        } finally {
            try {writer.close();} catch (Exception ex) {/*ignore*/}
        }
    }

    public static void launchCodeTimeMetricsDashboard() {
        Project p = getOpenProject();
        if (p == null) {
            return;
        }

        fetchCodeTimeMetricsContent();

        String codeTimeFile = SoftwareCoSessionManager.getCodeTimeDashboardFile();
        File f = new File(codeTimeFile);

        VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
        OpenFileDescriptor descriptor = new OpenFileDescriptor(p, vFile);
        FileEditorManager.getInstance(p).openTextEditor(descriptor, true);

        // delete the legacy file if we have one
        String legacyFileName = codeTimeFile.substring(0, codeTimeFile.lastIndexOf("."));
        File legacyFile = new File(legacyFileName);
        if (legacyFile.exists()) {
            legacyFile.delete();
        }
    }

    private static Pattern patternMacPairs = Pattern.compile("^([a-fA-F0-9]{2}[:\\.-]?){5}[a-fA-F0-9]{2}$");
    private static Pattern patternMacTriples = Pattern.compile("^([a-fA-F0-9]{3}[:\\.-]?){3}[a-fA-F0-9]{3}$");
    private static Pattern patternMac = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$");

    public static boolean isMacEmail(String email) {
        if (email.contains("_")) {
            String[] parts = email.split("_");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (patternMacPairs.matcher(part).find()
                        || patternMacTriples.matcher(part).find()
                        || patternMac.matcher(part).find()) {
                    return true;
                }
            }
        } else if (patternMacPairs.matcher(email).find()
                || patternMacTriples.matcher(email).find()
                || patternMac.matcher(email).find()) {
            return true;
        }
        return false;
    }

    public static String getIdentity() {
        String identityId = null;

        try {
            List<String> cmd = new ArrayList<String>();
            if (SoftwareCo.isWindows()) {
                cmd.add("cmd");
                cmd.add("/c");
                cmd.add("wmic nic get MACAddress");
            } else {
                cmd.add("/bin/sh");
                cmd.add("-c");
                cmd.add("ifconfig | grep \"ether \" | grep -v 127.0.0.1 | cut -d \" \" -f2");
            }

            String[] cmdArgs = Arrays.copyOf(cmd.toArray(), cmd.size(), String[].class);
            String content = SoftwareCoUtils.runCommand(cmdArgs, null);

            // for now just get the 1st one found
            if (content != null) {
                String[] contentList = content.split("\n");
                if (contentList != null && contentList.length > 0) {
                    for (String line : contentList) {
                        if ( line != null && line.trim().length() > 0 &&
                                ( patternMacPairs.matcher(line).find()
                                        || patternMacTriples.matcher(line).find()
                                        || patternMac.matcher(line).find() ) ) {
                            identityId = line.trim();
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            //
        }
        String username = SoftwareCo.getOsUserName();
        List<String> parts = new ArrayList<>();
        if (username != null) {
            parts.add(username);
        }
        if (identityId != null) {
            parts.add(identityId);
        }
        identityId = StringUtils.join(parts, "_");
        return identityId;
    }

    public static String getJsonObjString(JsonObject obj, String key) {
        if (obj != null && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    public static Long getJsonObjLong(JsonObject obj, String key) {
        if (obj != null && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsLong();
        }
        return null;
    }

    public static String getAppJwt(String identityId) {
        // clear out the previous app_jwt
        SoftwareCoSessionManager.setItem("app_jwt", null);

        boolean serverIsOnline = SoftwareCoSessionManager.isServerOnline();

        if (serverIsOnline) {
            if (identityId != null) {
                String encodedMacIdentity = "";
                try {
                    encodedMacIdentity = URLEncoder.encode(identityId, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    // url encoding failed, just use the mac addr id
                    encodedMacIdentity = identityId;
                }

                String api = "/data/token?addr=" + encodedMacIdentity;
                SoftwareResponse resp = SoftwareCoUtils.makeApiCall(api, HttpGet.METHOD_NAME, null);
                if (resp.isOk()) {
                    JsonObject obj = resp.getJsonObj();
                    return obj.get("jwt").getAsString();
                }
            }
        }
        return null;
    }

    public static void createAnonymousUser(String identityId) {
        boolean serverIsOnline = SoftwareCoSessionManager.isServerOnline();
        String pluginToken = SoftwareCoSessionManager.getItem("token");
        // make sure we've fetched the app jwt
        String appJwt = getAppJwt(identityId);

        if (serverIsOnline && identityId != null) {
            String email = identityId;
            if (pluginToken == null) {
                pluginToken = SoftwareCoSessionManager.generateToken();
                SoftwareCoSessionManager.setItem("token", pluginToken);
            }
            String timezone = TimeZone.getDefault().getID();

            String encodedMacIdentity = "";
            try {
                encodedMacIdentity = URLEncoder.encode(identityId, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // url encoding failed, just use the mac addr id
                encodedMacIdentity = identityId;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("email", email);
            payload.addProperty("plugin_token", pluginToken);
            payload.addProperty("timezone", timezone);
            String api = "/data/onboard?addr=" + encodedMacIdentity;
            SoftwareResponse resp = SoftwareCoUtils.makeApiCall(api, HttpPost.METHOD_NAME, payload.toString(), appJwt);
            if (resp.isOk()) {
                // check if we have the data and jwt
                // resp.data.jwt and resp.data.user
                // then update the session.json for the jwt, user, and jetbrains_lastUpdateTime
                JsonObject data = resp.getJsonObj();
                // check if we have any data
                if (data != null && data.has("jwt")) {
                    String dataJwt = data.get("jwt").getAsString();
                    String user = data.get("user").getAsString();
                    SoftwareCoSessionManager.setItem("jwt", dataJwt);
                    SoftwareCoSessionManager.setItem("user", user);
                }
            }
        }
    }

    public static List<User> getAuthenticatedPluginAccounts(String identityId) {
        List<User> users = new ArrayList<>();
        boolean serverIsOnline = SoftwareCoSessionManager.isServerOnline();

        // mac addr query str
        String api = "/users/plugin/accounts?token=";
        try {
            String encodedMacIdentity = URLEncoder.encode(identityId, "UTF-8");
            api += encodedMacIdentity;
        } catch (UnsupportedEncodingException e) {
            // url encoding failed, just use the mac addr id
            api += identityId;
        }

        if (serverIsOnline) {
            SoftwareResponse resp = SoftwareCoUtils.makeApiCall(api, HttpGet.METHOD_NAME, null);
            if (resp.isOk()) {
                JsonObject data = resp.getJsonObj();
                // check if we have any data
                if (data != null && data.has("users")) {
                    try {
                        JsonArray jsonUsers = data.getAsJsonArray("users");
                        if (jsonUsers != null && jsonUsers.size() > 0) {
                            for (JsonElement userObj : jsonUsers) {
                                JsonObject obj = (JsonObject)userObj;
                                User user = new User();
                                user.email = getJsonObjString(obj, "email");
                                user.mac_addr = getJsonObjString(obj, "mac_addr");
                                user.mac_addr_share = getJsonObjString(obj, "mac_addr_share");
                                user.plugin_jwt = getJsonObjString(obj, "plugin_jwt");
                                user.id = getJsonObjLong(obj, "id");
                                users.add(user);
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("error: " + e.getMessage());
                    }
                }
            }
        }

        return users;
    }

    public static User getLoggedInUser(String identityId, List<User> authAccounts) {
        if (authAccounts != null && authAccounts.size() > 0) {
            for (User user : authAccounts) {
                String userMacAddr = (user.mac_addr != null) ? user.mac_addr : "";
                String userEmail = (user.email != null) ? user.email : "";
                String userMacAddrShare = (user.mac_addr_share != null) ? user.mac_addr_share : "";
                if (!userEmail.equals(userMacAddr) &&
                    !userEmail.equals(identityId) &&
                    !userEmail.equals(userMacAddrShare) &&
                    userMacAddr.equals(identityId)) {
                    return user;
                }
            }
        }
        return null;
    }


    public static boolean hasRegisteredUserAccount(String identityId, List<User> authAccounts) {
        if (authAccounts != null && authAccounts.size() > 0) {
            for (User user : authAccounts) {
                String userMacAddr = (user.mac_addr != null) ? user.mac_addr : "";
                String userEmail = (user.email != null) ? user.email : "";
                String userMacAddrShare = (user.mac_addr_share != null) ? user.mac_addr_share : "";
                if (!userEmail.equals(userMacAddr) &&
                        !userEmail.equals(identityId) &&
                        !userEmail.equals(userMacAddrShare)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static User getAnonymousUser(List<User> authAccounts) {
        if (authAccounts != null && authAccounts.size() > 0) {
            for (User user : authAccounts) {
                if (user.email != null && SoftwareCoUtils.isMacEmail(user.email)) {
                    return user;
                }
            }
        }
        return null;
    }

    private static void updateSessionUser(User user) {
        JsonObject userObj = new JsonObject();
        userObj.addProperty("id", user.id);
        SoftwareCoSessionManager.setItem("jwt", user.plugin_jwt);
        SoftwareCoSessionManager.setItem("user", userObj.toString());
        SoftwareCoSessionManager.setItem("jetbrains_lastUpdateTime", String.valueOf(System.currentTimeMillis()));
    }

    public static UserStatus getUserStatus() {
        long nowMillis = System.currentTimeMillis();
        if (currentUserStatus != null && lastRegisterUserCheck != null) {
            if (nowMillis - lastRegisterUserCheck.longValue() <= 5000) {
                return currentUserStatus;
            }
        }

        String identityId = getIdentity();

        if (currentUserStatus == null) {
            currentUserStatus = new UserStatus();
        }

        try {
            List<User> authAccounts = getAuthenticatedPluginAccounts(identityId);
            User loggedInUser = getLoggedInUser(identityId, authAccounts);
            User anonUser = getAnonymousUser(authAccounts);
            if (anonUser == null) {
                // create the anonymous user
                createAnonymousUser(identityId);
                authAccounts = getAuthenticatedPluginAccounts(identityId);
                anonUser = getAnonymousUser(authAccounts);
            }
            boolean hasUserAccounts = hasRegisteredUserAccount(identityId, authAccounts);

            if (loggedInUser != null) {
                updateSessionUser(loggedInUser);
            } else if (anonUser != null) {
                updateSessionUser(anonUser);
            }


            currentUserStatus.loggedInUser = loggedInUser;
            currentUserStatus.hasUserAccounts = hasUserAccounts;

            if (currentUserStatus.loggedInUser != null) {
                currentUserStatus.email = currentUserStatus.loggedInUser.email;
            } else {
                currentUserStatus.email = null;
            }
        } catch (Exception e) {
            //
        }

        lastRegisterUserCheck = System.currentTimeMillis();


        return currentUserStatus;
    }

    public static void pluginLogout() {
        String api = "/users/plugin/logout";
        SoftwareResponse resp = SoftwareCoUtils.makeApiCall(api, HttpPost.METHOD_NAME, null);

        clearUserStatusCache();

        getUserStatus();

        new Thread(() -> {
            try {
                Thread.sleep(1000);
                SoftwareCoSessionManager.getInstance().fetchDailyKpmSessionInfo();
            }
            catch (Exception e){
                System.err.println(e);
            }
        }).start();
    }

}
