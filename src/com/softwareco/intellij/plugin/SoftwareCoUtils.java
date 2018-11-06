/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import java.util.Calendar;
import java.util.Date;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.WindowManager;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SoftwareCoUtils {

    public static final Logger log = Logger.getInstance("SoftwareCoUtils");

    // set the api endpoint to use
    public final static String api_endpoint = "https://api.software.com";
    // set the launch url to use
    public final static String launch_url = "https://app.software.com";

    public static ExecutorService executorService;
    public static HttpClient httpClient;

    private final static int EOF = -1;

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

    public static class HttpResponseInfo {
        public boolean isOk = false;
        public String jsonStr = null;
        public JsonObject jsonObj = null;
    }

    public static HttpResponseInfo getResponseInfo(HttpResponse response) {
        HttpResponseInfo responseInfo = new HttpResponseInfo();
        if (response != null) {
            try {
                // get the entity json string
                // (consume the entity so there's no connection leak causing a connection pool timeout)
                String jsonStr = getStringRepresentation(response.getEntity());
                if (jsonStr != null) {
                    responseInfo.jsonStr = jsonStr;
                    if (jsonStr.indexOf("{") != -1 && jsonStr.indexOf("}") != -1) {
                        JsonElement jsonEl = null;
                        try {
                            jsonEl = SoftwareCo.jsonParser.parse(jsonStr);
                            JsonObject jsonObj = jsonEl.getAsJsonObject();
                            responseInfo.jsonObj = jsonObj;
                        } catch (Exception e) {
                            // the string may be a simple message like "Unauthorized"
                        }
                    }
                }
                responseInfo.isOk = isOk(response);
            } catch (Exception e) {
                log.error("Unable to get http response info.", e);
            }

        } else {
            responseInfo.jsonStr = "Unauthorized";
            responseInfo.isOk = false;
        }
        return responseInfo;
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

        log.info("Software.com: executing request "
                + "[method: " + req.getMethod() + ", URI: " + req.getURI()
                + ", payload: " + payload + "]");
    }

    public static synchronized void setStatusLineMessage(
            final String singleMsg, final String tooltip) {
        setStatusLineMessage(singleMsg, null, null, null, tooltip);
    }

    public static synchronized void setStatusLineMessage(
            final String singleIcon, final String singleMsg,
            final String tooltip) {
        setStatusLineMessage(singleIcon, singleMsg, null, null, tooltip);
    }

    public static synchronized void setStatusLineMessage(
            final String kpmIcon, final String kpmMsg,
            final String timeIcon, final String timeMsg,
            final String tooltip) {
        try {
            Project p = ProjectManager.getInstance().getOpenProjects()[0];
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

    public static String getCurrentMusicTrack() {
        if (!SoftwareCo.isMac()) {
            return SoftwareCo.gson.toJson(new JsonObject());
        }
        String script =
                "on buildItunesRecord(appState)\n" +
                    "tell application \"iTunes\"\n" +
                        "set track_artist to artist of current track\n" +
                        "set track_name to name of current track\n" +
                        "set track_genre to genre of current track\n" +
                        "set track_id to database ID of current track\n" +
                        "set json to \"genre='\" & track_genre & \"';artist='\" & track_artist & \"';id='\" & track_id & \"';name='\" & track_name & \"';state='playing'\"\n" +
                    "end tell\n" +
                    "return json\n" +
                "end buildItunesRecord\n" +
                "on buildSpotifyRecord(appState)\n\n" +
                    "tell application \"Spotify\"\n" +
                        "set track_artist to artist of current track\n" +
                        "set track_name to name of current track\n" +
                        "set track_duration to duration of current track\n" +
                        "set track_id to id of current track\n" +
                        "set json to \"genre='';artist='\" & track_artist & \"';id='\" & track_id & \"';name='\" & track_name & \"';state='playing'\"\n" +
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
        return SoftwareCo.gson.toJson(jsonObj);
    }

    /**
     * Execute the args
     * @param args
     * @return
     */
    private static String runCommand(String[] args, String dir) {
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

    public static JsonObject getResourceInfo(String projectDir) {
        JsonObject jsonObj = new JsonObject();

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
                    jsonObj.addProperty("identifier", identifier);
                    jsonObj.addProperty("branch", branch);
                    jsonObj.addProperty("email", email);
                    jsonObj.addProperty("tag", tag);
                }
            } catch (Exception e) {
                //
            }
        }

        return jsonObj;
    }

}
