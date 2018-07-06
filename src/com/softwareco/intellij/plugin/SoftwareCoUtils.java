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

    // public final static String PLUGIN_MGR_ENDPOINT = "http://localhost:19234/api/v1/data";
    private final static String PROD_API_ENDPOINT = "https://api.software.com";
    private final static String PROD_URL_ENDPOINT = "https://app.software.com";

    // set the api endpoint to use
    public final static String api_endpoint = PROD_API_ENDPOINT;
    // set the launch url to use
    public final static String launch_url = PROD_URL_ENDPOINT;

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

//		String headers = "";
//		StringBuffer sb = new StringBuffer();
//		if (req.getAllHeaders() != null) {
//			for (Header header : req.getAllHeaders()) {
//				sb.append(header.getName()).append("=").append(header.getValue());
//				sb.append(",");
//			}
//		}
//		if (sb.length() > 0) {
//			headers = sb.toString();
//			headers = "{" + headers.substring(0, headers.lastIndexOf(",")) + "}";
//		}

        log.info("Software.com: executing request "
                + "[method: " + req.getMethod() + ", URI: " + req.getURI()
                + ", payload: " + payload + "]");
    }

    public static synchronized void setStatusLineMessage(final String msg, final String tooltip, final String iconName) {
        try {
            Project p = ProjectManager.getInstance().getOpenProjects()[0];
            final StatusBar statusBar = WindowManager.getInstance().getStatusBar(p);

            if (statusBar != null) {
                if (iconName != null && !iconName.equals("")) {
                    try {
                        statusBar.removeWidget(SoftwareCoStatusBarIconWidget.WIDGET_ID);
                    } catch (Exception e) {
                        //
                    }
                    Icon icon = IconLoader.findIcon("/com/softwareco/intellij/plugin/" + iconName + ".gif");
                    SoftwareCoStatusBarIconWidget iconWidget = new SoftwareCoStatusBarIconWidget();
                    iconWidget.setIcon(icon);
                    statusBar.addWidget(iconWidget);
                    statusBar.updateWidget(SoftwareCoStatusBarIconWidget.WIDGET_ID);
                }

                try {
                    statusBar.removeWidget(SoftwareCoStatusBarTextWidget.WIDGET_ID);
                } catch (Exception e) {
                    //
                }

                SoftwareCoStatusBarTextWidget widget = new SoftwareCoStatusBarTextWidget();
                widget.setText(msg);
                widget.setTooltip(tooltip);
                statusBar.addWidget(widget);
                statusBar.updateWidget(SoftwareCoStatusBarTextWidget.WIDGET_ID);

            }
        } catch (Exception e) {
            //
        }
    }

}
