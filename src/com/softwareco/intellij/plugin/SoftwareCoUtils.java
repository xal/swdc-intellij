/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.WindowManager;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.HttpClientBuilder;

import javax.swing.*;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SoftwareCoUtils {

    public static final Logger log = Logger.getInstance("SoftwareCoUtils");

    // set the api endpoint to use
    public final static String api_endpoint = "http://localhost:5000"; // "https://api.software.com";
    // set the launch url to use
    public final static String launch_url = "http://localhost:3000"; // "https://app.software.com";

    public static ExecutorService executorService;
    public static HttpClient httpClient;

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
            final String kpmText, final String kpmIconName,
            final String sessionTimeText, final String sessionTimeIconName,
            final String tooltip) {
        try {
            Project p = ProjectManager.getInstance().getOpenProjects()[0];
            final StatusBar statusBar = WindowManager.getInstance().getStatusBar(p);

            if (statusBar != null) {
                // remove existing widgets
                try {
                    statusBar.removeWidget(SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID);
                } catch (Exception e) {
                    //
                }
                try {
                    statusBar.removeWidget(SoftwareCoStatusBarKpmTextWidget.SESSION_TIME_TEXT_ID);
                } catch (Exception e) {
                    //
                }
                try {
                    statusBar.removeWidget(SoftwareCoStatusBarKpmTextWidget.TEXT_SEPARATOR);
                } catch (Exception e) {
                    //
                }
                try {
                    statusBar.removeWidget(SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID);
                } catch (Exception e) {
                    //
                }
                try {
                    statusBar.removeWidget(SoftwareCoStatusBarKpmIconWidget.SESSION_TIME_ICON_ID);
                } catch (Exception e) {
                    //
                }

                // add the kpm icon if it's passed in
                if (kpmIconName != null && !kpmIconName.equals("")) {
                    Icon icon = IconLoader.findIcon("/com/softwareco/intellij/plugin/assets/dark/" + kpmIconName);

                    SoftwareCoStatusBarKpmIconWidget iconWidget =
                            new SoftwareCoStatusBarKpmIconWidget(SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID);
                    iconWidget.setIcon(icon);
                    statusBar.addWidget(iconWidget);
                    statusBar.updateWidget(SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID);
                }

                // add the kpm text, it can also just be "Software.com"
                final String kpmMsg = (kpmText == null || kpmText.equals("")) ? "Software.com" : kpmText;
                SoftwareCoStatusBarKpmTextWidget widget =
                        new SoftwareCoStatusBarKpmTextWidget(SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID);
                widget.setText(kpmMsg);
                widget.setTooltip(tooltip);
                statusBar.addWidget(widget);
                statusBar.updateWidget(SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID);

                boolean emptySessionIcon = (sessionTimeIconName != null && !sessionTimeIconName.equals(""))
                        ? false : true;

                // add the separator if we have a session time text
                if (sessionTimeText != null && !sessionTimeText.equals("") && !emptySessionIcon) {
                    SoftwareCoStatusBarKpmTextWidget textSepWidget =
                            new SoftwareCoStatusBarKpmTextWidget(SoftwareCoStatusBarKpmTextWidget.TEXT_SEPARATOR);
                    textSepWidget.setText(", ");
                    statusBar.addWidget(textSepWidget);
                    statusBar.updateWidget(SoftwareCoStatusBarKpmTextWidget.TEXT_SEPARATOR);
                }

                // add the session time icon if its passed in
                if (!emptySessionIcon) {
                    Icon icon = IconLoader.findIcon("/com/softwareco/intellij/plugin/assets/dark/" + sessionTimeIconName);

                    SoftwareCoStatusBarKpmIconWidget iconWidget =
                            new SoftwareCoStatusBarKpmIconWidget(SoftwareCoStatusBarKpmIconWidget.SESSION_TIME_ICON_ID);
                    iconWidget.setIcon(icon);
                    statusBar.addWidget(iconWidget);
                    statusBar.updateWidget(SoftwareCoStatusBarKpmIconWidget.SESSION_TIME_ICON_ID);
                }

                // add the session time text if its passed in
                if (sessionTimeText != null && !sessionTimeText.equals("")) {
                    SoftwareCoStatusBarKpmTextWidget sessionTimeWidget =
                            new SoftwareCoStatusBarKpmTextWidget(SoftwareCoStatusBarKpmTextWidget.SESSION_TIME_TEXT_ID);
                    sessionTimeWidget.setText(sessionTimeText);
                    sessionTimeWidget.setTooltip(tooltip);
                    statusBar.addWidget(sessionTimeWidget);
                    statusBar.updateWidget(SoftwareCoStatusBarKpmTextWidget.SESSION_TIME_TEXT_ID);
                }

            }
        } catch (Exception e) {
            //
        }
    }

}
