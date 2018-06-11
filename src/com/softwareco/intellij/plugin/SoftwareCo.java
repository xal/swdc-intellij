/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PlatformUtils;
import com.intellij.util.messages.MessageBusConnection;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

/**
 * Intellij Plugin Application.
 */
public class SoftwareCo implements ApplicationComponent {

    public static JsonParser jsonParser = new JsonParser();
    public static final Logger log = Logger.getInstance("SoftwareCo");
    public static Gson gson;

    private static final String PLUGIN_MGR_ENDPOINT = "http://localhost:19234/api/v1/data";
    private static final String PM_BUCKET = "https://s3-us-west-1.amazonaws.com/swdc-plugin-manager/";
    private static final String PM_NAME = "software";

    private String VERSION;
    private String IDE_NAME;
    private String IDE_VERSION;
    private MessageBusConnection[] connections;

    private final int SEND_INTERVAL = 60;
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> scheduledFixture;

    private static HttpClient httpClient;
    private static ExecutorService executorService;
    private static boolean pluginManagerConnectionErrorShown = false;
    private static boolean READY = false;
    private static KeystrokeManager keystrokeMgr;
    private static boolean downloadingPM = false;
    private static boolean pluginManagerInstallErrorShown = false;

    public SoftwareCo() {
    }

    public void initComponent() {

        VERSION = PluginManager.getPlugin(PluginId.getId("com.softwareco.intellij.plugin")).getVersion();
        log.info("Software.com: Loaded v" + VERSION);

        // Set runtime constants
        IDE_NAME = PlatformUtils.getPlatformPrefix();
        IDE_VERSION = ApplicationInfo.getInstance().getFullVersion();

        setLoggingLevel();

        executorService = Executors.newFixedThreadPool(2);

        keystrokeMgr = KeystrokeManager.getInstance();
        // initialize the HttpClient
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setConnectionRequestTimeout(5000)
                .setSocketTimeout(5000)
                .build();

        httpClient = HttpClientBuilder
                .create()
                .setDefaultRequestConfig(config)
                .build();
        gson = new Gson();

        setupEventListeners();
        setupScheduledProcessor();
        log.info("Software.com: Finished initializing SoftwareCo plugin");

        READY = true;
    }

    private void setupEventListeners() {
        ApplicationManager.getApplication().invokeLater(new Runnable(){
            public void run() {
                // edit document
                EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new SoftwareCoDocumentListener());

                Project[] projects = ProjectManager.getInstance().getOpenProjects();
                if (projects != null) {
                    connections = new MessageBusConnection[projects.length];
                    for (int i = 0; i < projects.length; i++) {
                        Project project = projects[i];
                        MessageBusConnection connection = project.getMessageBus().connect(project);
                        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new SoftwareCoFileEditorListener());
                        connections[i] = connection;
                        keystrokeMgr.addKeystrokeWrapperIfNoneExists(project);
                    }
                }
            }
        });
    }

    private void setupScheduledProcessor() {
        final Runnable handler = () -> processKeystrokes();
        scheduledFixture = scheduler.scheduleAtFixedRate(
                handler, SEND_INTERVAL, SEND_INTERVAL, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void disposeComponent() {
        try {
            if (connections != null) {
                for (MessageBusConnection connection : connections) {
                    connection.disconnect();
                }
            }
        } catch(Exception e) {
            log.debug("Error disconnecting the software.com plugin, reason: " + e.toString());
        }
        try {
            scheduledFixture.cancel(true);
        } catch (Exception e) {
            log.debug("Error cancelling the software.com plugin KPM processing schedule, reason: " + e.toString());
        }

        // process one last time
        // this will ensure we process the latest keystroke updates
        processKeystrokes();
    }

    public static void setLoggingLevel() {
        log.setLevel(Level.INFO);
    }

    @NotNull
    public String getComponentName() {
        return "SoftwareCo";
    }

    public static void handleFileOpenedEvents(String fileName, Project project) {
        KeystrokeCount keystrokeCount = keystrokeMgr.getKeystrokeCount(project.getName());
        if (keystrokeCount == null) {
            initializeKeystrokeObjectGraph(fileName, project.getName(), project.getProjectFilePath());
            keystrokeCount = keystrokeMgr.getKeystrokeCount(project.getName());
        }
        JsonObject fileInfo = keystrokeCount.getSourceByFileName(fileName);
        if (fileInfo == null) {
            return;
        }
        updateFileInfoValue(fileInfo, fileName, "open", 1);
        log.info("Software.com: file opened: " + fileName);
    }

    public static void handleFileClosedEvents(String fileName, Project project) {
        KeystrokeCount keystrokeCount = keystrokeMgr.getKeystrokeCount(project.getName());
        if (keystrokeCount == null) {
            initializeKeystrokeObjectGraph(fileName, project.getName(), project.getProjectFilePath());
            keystrokeCount = keystrokeMgr.getKeystrokeCount(project.getName());
        }
        JsonObject fileInfo = keystrokeCount.getSourceByFileName(fileName);
        if (fileInfo == null) {
            return;
        }
        updateFileInfoValue(fileInfo, fileName, "close", 1);
        log.info("Software.com: file closed: " + fileName);
    }

    private static String getFileUrl() {
        String fileUrl = PM_BUCKET + PM_NAME;
        if (SystemInfo.isWindows) {
            fileUrl += ".exe";
        } else if (SystemInfo.isMac) {
            fileUrl += ".dmg";
        } else {
            fileUrl += ".deb";
        }
        return fileUrl;
    }

    private static String getDownloadFilePathName() {
        String downloadFilePathName = System.getProperty("user.home");
        if (SystemInfo.isWindows) {
            downloadFilePathName += "\\Desktop\\" + PM_NAME + ".exe";
        } else if (SystemInfo.isMac) {
            downloadFilePathName += "/Desktop/" + PM_NAME + ".dmg";
        } else {
            downloadFilePathName += "/Desktop/" + PM_NAME + ".deb";
        }

        return downloadFilePathName;
    }

    private static String getPmInstallDirectoryPath() {
        if (SystemInfo.isWindows) {
            return System.getProperty("user.home") + "\\AppData\\Programs";
        } else if (SystemInfo.isMac) {
            return "/Applications";
        } else {
            return "/user/lib";
        }
    }

    private static boolean hasPluginInstalled() {
        String installDir = getPmInstallDirectoryPath();
        File f = new File(installDir);
        if (f.exists() && f.isDirectory()) {
            for (File file : f.listFiles()) {
                if (!file.isDirectory() && file.getName().toLowerCase().indexOf("software") == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void downloadPM() {
        downloadingPM = true;
        String saveAs = getDownloadFilePathName();

        URL downloadUrl = null;
        try {
            downloadUrl = new URL(getFileUrl());
        } catch (MalformedURLException e) {}

        ReadableByteChannel rbc = null;
        FileOutputStream fos = null;
        try {
            rbc = Channels.newChannel(downloadUrl.openStream());
            fos = new FileOutputStream(saveAs);
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            fos.close();
            log.info("Completed download of " + saveAs);
            Desktop.getDesktop().open(new File(saveAs));
        } catch (Exception e) {
            log.error("Failed to download plugin manager, reason: " + e.toString());
        }
        downloadingPM = false;
    }

    /**
     * Handles character change events in a file
     * @param document
     * @param documentEvent
     */
    public static void handleChangeEvents(Document document, DocumentEvent documentEvent) {

        if (document == null) {
            return;
        }

        //
        // Get the current editors so we can determine the current file name and project in view
        //
        Editor[] editors = EditorFactory.getInstance().getEditors(document);
        if (editors == null || editors.length == 0) {
            //
            // No editors to get the file and project information out of
            //
            return;
        }

        String projectName = "";
        String projectFilepath = "";
        String fileName = "";

        //
        // Get the file and project information from the document manager
        //
        FileDocumentManager instance = FileDocumentManager.getInstance();

        VirtualFile file = null;
        if (instance != null) {
            file = instance.getFile(document);
        }

        Project project = editors[0].getProject();

        if (project != null) {
            projectName = project.getName();
            projectFilepath = project.getProjectFilePath();
        }

        keystrokeMgr.addKeystrokeWrapperIfNoneExists(project);

        if (file != null) {
            fileName = file.getPath();
        }

        //
        // project file path may be something like...
        // /Users/username/IdeaProjects/MyProject/.idea/misc.xml
        // so we need to extract the base project path using the project name
        // which will be "MyProject"
        //
        if (projectName != null && projectName.length() > 0 &&
                projectFilepath != null &&
                projectFilepath.length() > 0) {
            // get the index of the project name from the project path
            int projectNameIdx = projectFilepath.indexOf(projectName);
            if (projectNameIdx > 0) {
                projectFilepath = projectFilepath.substring(0, projectNameIdx - 1);
            }
        }

        //
        // The docEvent length will not be zero if the user deleted
        // a number of characters at once,
        //
        int numKeystrokes = (documentEvent != null) ?
                documentEvent.getNewLength() - documentEvent.getOldLength() :
                0;

        KeystrokeManager.KeystrokeCountWrapper wrapper = keystrokeMgr.getKeystrokeWrapper(projectName);

        //
        // get keystroke count from document length change if we're still in the same doc. That
        // means it was a document save or event.
        //
        if ( numKeystrokes == 0 && fileName.equals(wrapper.getCurrentFileName()) ) {
            //
            // Count the number of characters in the text attribute
            //
            numKeystrokes = document.getTextLength() - wrapper.getCurrentTextLength();
        }

        //
        // Set the current text length and the current file and the current project
        //
        int currLen = document.getTextLength();
        wrapper.setCurrentFileName(fileName);
        wrapper.setCurrentTextLength(currLen);

        initializeKeystrokeObjectGraph(fileName, projectName, projectFilepath);

        KeystrokeCount keystrokeCount = keystrokeMgr.getKeystrokeCount(projectName);

        JsonObject fileInfo = keystrokeCount.getSourceByFileName(fileName);

        if (numKeystrokes > 1) {
            // It's a copy and paste event
            updateFileInfoValue(fileInfo, fileName, "paste", numKeystrokes);

            log.info("Software.com: Copy+Paste incremented");
        } else if (numKeystrokes < 0) {
            // It's a character delete event
            updateFileInfoValue(fileInfo, fileName, "delete", Math.abs(numKeystrokes));

            log.info("Software.com: Delete incremented");
        } else {
            // increment the specific file keystroke value
            updateFileInfoValue(fileInfo, fileName, "keys", 1);

            // increment the data keystroke count
            int incrementedCount = Integer.parseInt(keystrokeCount.getData()) + 1;
            keystrokeCount.setData( String.valueOf(incrementedCount) );

            log.info("Software.com: KPM incremented");
        }

        updateFileInfoValue(fileInfo, fileName, "length", currLen);
    }

    private static void updateFileInfoValue(JsonObject fileInfo, String fileName, String key, int incrementVal) {
        JsonPrimitive keysVal = fileInfo.getAsJsonPrimitive(key);
        if (key.equals("length")) {
            // length isn't additive
            fileInfo.addProperty(key, incrementVal);
        } else {
            int totalVal = keysVal.getAsInt() + incrementVal;
            fileInfo.addProperty(key, totalVal);
        }
    }

    private static void initializeKeystrokeObjectGraph(String fileName, String projectName, String projectFilepath) {
        // initialize it in case it's not initialized yet
        initializeKeystrokeCount(projectName, projectFilepath);

        KeystrokeCount keystrokeCount = keystrokeMgr.getKeystrokeCount(projectName);

        //
        // Make sure we have the project name and directory info
        updateKeystrokeProject(projectName, fileName, keystrokeCount);
    }

    private static void initializeKeystrokeCount(String projectName, String projectFilepath) {
        KeystrokeCount keystrokeCount = keystrokeMgr.getKeystrokeCount(projectName);
        if ( keystrokeCount == null ) {
            //
            // Create one since it hasn't been created yet
            // and set the start time (in seconds)
            //
            keystrokeCount = new KeystrokeCount();

            KeystrokeProject keystrokeProject = new KeystrokeProject( projectName, projectFilepath );
            keystrokeCount.setProject( keystrokeProject );

            //
            // Update the manager with the newly created KeystrokeCount object
            //
            keystrokeMgr.setKeystrokeCount(projectName, keystrokeCount);
        }
    }

    private static void updateKeystrokeProject(String projectName, String fileName, KeystrokeCount keystrokeCount) {
        if (keystrokeCount == null) {
            return;
        }
        KeystrokeProject project = keystrokeCount.getProject();
        String projectDirectory = getProjectDirectory(projectName, fileName);

        if (project == null) {
            project = new KeystrokeProject( projectName, projectDirectory );
            keystrokeCount.setProject( project );
        } else if (project.getName() == null || project.getName() == "") {
            project.setDirectory(projectDirectory);
            project.setName(projectName);
        }
    }

    private static String getProjectDirectory(String projectName, String fileName) {
        String projectDirectory = "";
        if ( projectName != null && projectName.length() > 0 &&
                fileName != null && fileName.length() > 0 &&
                fileName.indexOf(projectName) > 0 ) {
            projectDirectory = fileName.substring( 0, fileName.indexOf( projectName ) - 1 );
        }
        return projectDirectory;
    }

    private static void processKeystrokes() {
        if (READY) {

            List<KeystrokeManager.KeystrokeCountWrapper> wrappers = keystrokeMgr.getKeystrokeCountWrapperList();
            long nowInMillis = System.currentTimeMillis();
            for (KeystrokeManager.KeystrokeCountWrapper wrapper : wrappers) {
                if (wrapper.getKeystrokeCount() != null) {
                    //
                    // reset the current keystroke count object
                    //
                    // clone it to send to the http task
                    KeystrokeCount clone = wrapper.getKeystrokeCount().clone();
                    log.info("Resetting keystroke count data for project name wrapper: " + clone.getProject().getName());
                    keystrokeMgr.resetData(clone.getProject().getName());
                    if (clone.hasData()) {

                        //
                        // Send the info now
                        //
                        sendKeystrokeData(clone);
                    }
                }
            }
        }
    }

    private static void sendKeystrokeData(KeystrokeCount keystrokeCount) {

        KeystrokeDataSendTask sendTask = new KeystrokeDataSendTask(keystrokeCount);
        Future<HttpResponse> response = executorService.submit(sendTask);

        boolean postFailed = true;

        if (response != null) {
            HttpResponse r = null;
            try {
                r = response.get();

                //
                // Handle the response
                //
                String entityResult = "";
                if (r.getEntity() != null) {
                    try {
                        entityResult = EntityUtils.toString(r.getEntity());
                    } catch (Exception e) {
                        log.debug("Software.com: Unable to parse the non-null plugin manager response, reason: " + e.toString());
                    }
                }
                int responseStatus = r.getStatusLine().getStatusCode();

                //
                // Anything greater or equal to 300 http status code is not what the plugin expects, log the error
                //
                if (responseStatus >= 300) {
                    log.debug("Software.com: Unable to send the keystroke payload, "
                            + "response: [status: " + responseStatus + ", entityResult: '" + entityResult + "']");
                } else {
                    // only condition where postFailed will be set to false
                    postFailed = false;
                }
            } catch (Exception e) {
                log.debug("Software.com: Unable to get the response from the http request, reason: " + e.toString());
            }
        }

        if (!downloadingPM && postFailed && !pluginManagerConnectionErrorShown) {

            // first check to see if the plugin manager was installed
            if (!pluginManagerInstallErrorShown && !hasPluginInstalled()) {
                pluginManagerInstallErrorShown = true;
                ApplicationManager.getApplication().invokeLater(new Runnable() {
                    public void run() {
                        // ask to download the PM
                        int options = Messages.showDialog(
                                "We are having trouble sending data to Software.com. " +
                                        "The Plugin Manager may not be installed. Would you like to download it now?",
                                "Software", new String[]{"Download", "Not now"}, 0, Messages.getQuestionIcon());
                        if (options == 0) {
                            // "download" was selected
                            downloadPM();
                        }
                    }
                });
                return;
            }

            pluginManagerConnectionErrorShown = true;
            ApplicationManager.getApplication().invokeLater(new Runnable() {
                public void run() {
                    // show a popup
                    String msg = "We are having trouble sending data to Software.com. Please make sure the Plugin Manager is running and logged in.";
                    // show a popup message once
                    Messages.showMessageDialog(
                            msg,
                            "Information",
                            Messages.getInformationIcon());
                }
            });
        }
    }

    protected static class KeystrokeDataSendTask implements Callable<HttpResponse> {

        private KeystrokeCount keystrokeCount;

        public KeystrokeDataSendTask(KeystrokeCount keystrokeCount) {
            this.keystrokeCount = keystrokeCount;
        }

        @Override
        public HttpResponse call() throws Exception {
            //
            // create the json string
            //
            // set the start of this record to a minute and a half ago so that it's in the immediate past.
            long startInSeconds = (int) (new Date().getTime() / 1000 - 90);
            keystrokeCount.setStart(startInSeconds);
            keystrokeCount.setEnd(startInSeconds + 60);
            String keystrokeCountJson = gson.toJson(keystrokeCount);

            log.info("Software.com: sending:\n" + PLUGIN_MGR_ENDPOINT + "\n" + keystrokeCountJson);

            try {

                HttpPost request = new HttpPost(PLUGIN_MGR_ENDPOINT);
                StringEntity params = new StringEntity(keystrokeCountJson);
                request.addHeader("Content-type", "application/json");
                request.setEntity(params);

                //
                // Send the POST request
                //
                HttpResponse response = httpClient.execute(request);

                //
                // Return the response
                //
                return response;
            } catch (Exception e) {
                log.debug("Software.com: Unable to complete software.com request to the plugin manager, reason: " + e.toString());
            }

            return null;
        }
    }
}