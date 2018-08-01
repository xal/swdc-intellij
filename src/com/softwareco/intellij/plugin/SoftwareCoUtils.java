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
    public final static String api_endpoint = "http://localhost:5000"; //https://api.software.com";
    // set the launch url to use
    public final static String launch_url = "http://localhost:3000"; //https://app.software.com";

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
            final String statusMsg,
            final String tooltip) {
        try {
            Project p = ProjectManager.getInstance().getOpenProjects()[0];
            final StatusBar statusBar = WindowManager.getInstance().getStatusBar(p);

            if (statusBar != null) {
                removeWidgets();

                SoftwareCoStatusBarKpmTextWidget widget = buildStatusBarTextWidget(statusMsg, tooltip, SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID);
                statusBar.addWidget(widget);
                statusBar.updateWidget(SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID);
            }
        } catch (Exception e) {
            //
        }
    }

    public static synchronized void setStatusLineMessage(
            final String kpmStr, final String sessionStr, final String kpmIcon, final String sessionIcon,
            final String tooltip) {
        try {
            Project p = ProjectManager.getInstance().getOpenProjects()[0];
            final StatusBar statusBar = WindowManager.getInstance().getStatusBar(p);

            if (statusBar != null) {
                removeWidgets();

                if (kpmIcon != null && !kpmIcon.equals("")) {
                    SoftwareCoStatusBarKpmIconWidget iconWidget = buildStatusBarIconWidget(kpmIcon, tooltip, SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID);
                    statusBar.addWidget(iconWidget);
                    statusBar.updateWidget(SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID);
                }

                SoftwareCoStatusBarKpmTextWidget widget = buildStatusBarTextWidget(kpmStr, tooltip, SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID);
                statusBar.addWidget(widget);
                statusBar.updateWidget(SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID);

                if (sessionIcon != null && !sessionIcon.equals("")) {
                    SoftwareCoStatusBarKpmIconWidget iconWidget = buildStatusBarIconWidget(sessionIcon, tooltip, SoftwareCoStatusBarKpmIconWidget.SESSION_TIME_ICON_ID);
                    statusBar.addWidget(iconWidget);
                    statusBar.updateWidget(SoftwareCoStatusBarKpmIconWidget.SESSION_TIME_ICON_ID);
                }

                SoftwareCoStatusBarKpmTextWidget sessionTimeWidget = buildStatusBarTextWidget(sessionStr, tooltip, SoftwareCoStatusBarKpmTextWidget.SESSION_TIME_TEXT_ID);
                statusBar.addWidget(sessionTimeWidget);
                statusBar.updateWidget(SoftwareCoStatusBarKpmTextWidget.SESSION_TIME_TEXT_ID);
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
                if (statusBar.getWidget(SoftwareCoStatusBarKpmTextWidget.SESSION_TIME_TEXT_ID) != null) {
                    statusBar.removeWidget(SoftwareCoStatusBarKpmTextWidget.SESSION_TIME_TEXT_ID);
                }
                if (statusBar.getWidget(SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID) != null) {
                    statusBar.removeWidget(SoftwareCoStatusBarKpmTextWidget.KPM_TEXT_ID);
                }

                if (statusBar.getWidget(SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID) != null) {
                    statusBar.removeWidget(SoftwareCoStatusBarKpmIconWidget.KPM_ICON_ID);
                }
                if (statusBar.getWidget(SoftwareCoStatusBarKpmIconWidget.SESSION_TIME_ICON_ID) != null) {
                    statusBar.removeWidget(SoftwareCoStatusBarKpmIconWidget.SESSION_TIME_ICON_ID);
                }
            }
        } catch (Exception e) {
            //
        }
    }

    public static SoftwareCoStatusBarKpmIconWidget buildStatusBarIconWidget(String iconName, String tooltip, String ID) {
        Icon icon = IconLoader.findIcon("/com/softwareco/intellij/plugin/assets/dark/" + iconName);

        SoftwareCoStatusBarKpmIconWidget iconWidget =
                new SoftwareCoStatusBarKpmIconWidget(ID);
        iconWidget.setIcon(icon);
        iconWidget.setTooltip(tooltip);
        return iconWidget;
    }

    public static SoftwareCoStatusBarKpmTextWidget buildStatusBarTextWidget(String msg, String tooltip, String ID) {
        SoftwareCoStatusBarKpmTextWidget textWidget =
                new SoftwareCoStatusBarKpmTextWidget(ID);
        textWidget.setText(msg);
        textWidget.setTooltip(tooltip);
        return textWidget;
    }

}
