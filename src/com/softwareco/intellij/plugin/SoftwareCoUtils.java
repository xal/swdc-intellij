/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Calendar;
import java.util.Date;

import com.google.gson.JsonParser;
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
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.HttpClientBuilder;

import javax.swing.*;
import java.io.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SoftwareCoUtils {

    public static final Logger LOG = Logger.getLogger("SoftwareCoUtils");

    // set the api endpoint to use
    public final static String api_endpoint = "https://api.software.com";
    // set the launch url to use
    public final static String launch_url = "https://app.software.com";

    public static ExecutorService executorService;
    public static HttpClient httpClient;

    public static JsonParser jsonParser = new JsonParser();

    // sublime = 1, vs code = 2, eclipse = 3, intellij = 4, visual studio = 6, atom = 7
    public static int pluginId = 4;
    public static String version = "0.1.52";

    private final static ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();

    private final static int EOF = -1;

    private static boolean fetchingResourceInfo = false;
    private static JsonObject lastResourceInfo = new JsonObject();

    static {
        // initialize the HttpClient
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(10000)
                .setConnectionRequestTimeout(10000)
                .setSocketTimeout(10000)
                .build();

        httpClient = HttpClientBuilder
                .create()
                .setDefaultRequestConfig(config)
                .build();

        executorService = Executors.newCachedThreadPool();
    }

    public static SoftwareResponse makeApiCall(String api, String httpMethodName, String payload) {

        SoftwareResponse softwareResponse = new SoftwareResponse();
        if (!SoftwareCo.TELEMTRY_ON) {
            softwareResponse.setIsOk(true);
            return softwareResponse;
        }

        SoftwareHttpManager httpTask = new SoftwareHttpManager(api, httpMethodName, payload);
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
                    HttpEntity entity = httpResponse.getEntity();
                    JsonObject jsonObj = null;
                    if (entity != null) {
                        try {
                            ContentType contentType = ContentType.getOrDefault(entity);
                            String mimeType = contentType.getMimeType();
                            String jsonStr = getStringRepresentation(entity);
                            softwareResponse.setJsonStr(jsonStr);
                            LOG.log(Level.ALL.INFO, "Sofware.com: API response {0}", jsonStr);
                            if (jsonStr != null && !mimeType.equals("text/plain")) {
                                Object jsonEl = jsonParser.parse(jsonStr);

                                if (jsonEl instanceof JsonElement) {
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
        if (response == null || response.getStatusLine() == null || response.getStatusLine().getStatusCode() != 200) {
            return false;
        }
        return true;
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

                removeWidgets();

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
        } catch (Exception e) {
            //
        }
    }

    private static void removeWidgets() {
        try {
            Project p = ProjectManager.getInstance().getOpenProjects()[0];
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
            }
        } catch (Exception e) {
            //
        }
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
        String minutesStr = "";
        if (minutes == 60) {
            minutesStr = "1 hr";
        } else if (minutes > 60) {
            float fval = (float)minutes / 60;
            try {
                if (fval % 1 == 0) {
                    // don't return a number with 2 decimal place precision
                    minutesStr = String.format("%.0f", fval) + " hrs";
                } else {
                    minutesStr = String.format("%.2f", fval) + " hrs";
                }
            } catch (Exception e) {
                minutesStr = String.valueOf(fval);
            }
        } else if (minutes == 1) {
            minutesStr = "1 min";
        } else {
            minutesStr = minutes + " min";
        }
        return minutesStr;
    }

    public static JsonObject getCurrentMusicTrack() {
        if (!SoftwareCo.isMac()) {
            return new JsonObject();
        }
        String script =
                "on buildItunesRecord(appState)\n" +
                    "tell application \"iTunes\"\n" +
                        "set track_artist to artist of current track\n" +
                        "set track_name to name of current track\n" +
                        "set track_genre to genre of current track\n" +
                        "set track_id to database ID of current track\n" +
                        "set track_duration to duration of current track\n" +
                        "set json to \"type='itunes';genre='\" & track_genre & \"';artist='\" & track_artist & \"';id='\" & track_id & \"';name='\" & track_name & \"';state='playing';duration='\" & track_duration & \"'\"\n" +
                    "end tell\n" +
                    "return json\n" +
                "end buildItunesRecord\n" +
                "on buildSpotifyRecord(appState)\n\n" +
                    "tell application \"Spotify\"\n" +
                        "set track_artist to artist of current track\n" +
                        "set track_name to name of current track\n" +
                        "set track_duration to duration of current track\n" +
                        "set track_id to id of current track\n" +
                        "set track_duration to duration of current track\n" +
                        "set json to \"type='spotify';genre='';artist='\" & track_artist & \"';id='\" & track_id & \"';name='\" & track_name & \"';state='playing';duration='\" & track_duration & \"'\"\n" +
                    "end tell\n" +
                    "return json\n" +
                "end buildSpotifyRecord\n\n" +
                "try\n" +
                    "if application \"Spotify\" is running and application \"iTunes\" is not running then\n" +
                        "tell application \"Spotify\" to set spotifyState to (player state as text)\n" +
                        "-- spotify is running and itunes is not\n" +
                        "if (spotifyState is \"paused\" or spotifyState is \"playing\") then\n" +
                            "set jsonRecord to buildSpotifyRecord(spotifyState)\n" +
                        "else\n" +
                            "set jsonRecord to {}\n" +
                        "end if\n" +
                    "else if application \"Spotify\" is running and application \"iTunes\" is running then\n" +
                        "tell application \"Spotify\" to set spotifyState to (player state as text)\n" +
                        "tell application \"iTunes\" to set itunesState to (player state as text)\n" +
                        "-- both are running but use spotify as a higher priority\n" +
                        "if spotifyState is \"playing\" then\n" +
                            "set jsonRecord to buildSpotifyRecord(spotifyState)\n" +
                        "else if itunesState is \"playing\" then\n" +
                            "set jsonRecord to buildItunesRecord(itunesState)\n" +
                        "else if spotifyState is \"paused\" then\n" +
                            "set jsonRecord to buildSpotifyRecord(spotifyState)\n" +
                        "else\n" +
                            "set jsonRecord to {}\n" +
                        "end if\n" +
                    "else if application \"iTunes\" is running and application \"Spotify\" is not running then\n" +
                        "tell application \"iTunes\" to set itunesState to (player state as text)\n" +
                        "set jsonRecord to buildItunesRecord(itunesState)\n" +
                    "else\n" +
                        "set jsonRecord to {}\n" +
                    "end if\n" +
                    "return jsonRecord\n" +
                "on error\n" +
                    "return {}\n" +
                "end try";

        String[] args = { "osascript", "-e", script };
        String trackInfoStr = runCommand(args, null);
        // genre:Alternative, artist:AWOLNATION, id:6761, name:Kill Your Heroes, state:playing
        JsonObject jsonObj = new JsonObject();
        if (trackInfoStr != null && !trackInfoStr.equals("")) {
            // trim and replace things
            trackInfoStr = trackInfoStr.trim();
            trackInfoStr = trackInfoStr.replace("\"", "");
            trackInfoStr = trackInfoStr.replace("'", "");
            String[] paramParts = trackInfoStr.split(";");
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

    public static void launchCodeTimeMetricsDashboard() {
        Project p = getOpenProject();
        if (p == null) {
            return;
        }
        String api = "/dashboard";
        String dashboardContent = SoftwareCoUtils.makeApiCall(api, HttpGet.METHOD_NAME, null).getJsonStr();
        String codeTimeFile = SoftwareCoSessionManager.getCodeTimeDashboardFile();
        File f = new File(codeTimeFile);

        Writer writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(codeTimeFile), "utf-8"));
            writer.write(dashboardContent);
        } catch (IOException ex) {
            // Report
        } finally {
            try {writer.close();} catch (Exception ex) {/*ignore*/}
        }
        VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
        OpenFileDescriptor descriptor = new OpenFileDescriptor(p, vFile);
        FileEditorManager.getInstance(p).openTextEditor(descriptor, true);
    }

}
