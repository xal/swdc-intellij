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
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
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
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PlatformUtils;
import com.intellij.util.messages.MessageBusConnection;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;

/**
 * Intellij Plugin Application
 */
public class SoftwareCo implements ApplicationComponent {

    public static JsonParser jsonParser = new JsonParser();
    public static final Logger log = Logger.getInstance("SoftwareCo");
    public static Gson gson;

    public static boolean TELEMTRY_ON = true;

    private String VERSION;
    private String IDE_NAME;
    private String IDE_VERSION;
    private MessageBusConnection[] connections;

    private final int SEND_INTERVAL = 60;
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> scheduledFixture;

    private static boolean READY = false;
    private static KeystrokeManager keystrokeMgr;

    private SoftwareCoSessionManager sessionMgr = SoftwareCoSessionManager.getInstance();

    private Timer kpmFetchTimer;

    public SoftwareCo() {
    }

    public void initComponent() {

        VERSION = PluginManager.getPlugin(PluginId.getId("com.softwareco.intellij.plugin")).getVersion();
        log.info("Software.com: Loaded v" + VERSION);

        // Set runtime constants
        IDE_NAME = PlatformUtils.getPlatformPrefix();
        IDE_VERSION = ApplicationInfo.getInstance().getFullVersion();

        setLoggingLevel();

        keystrokeMgr = KeystrokeManager.getInstance();
        gson = new Gson();

        setupEventListeners();
        setupScheduledProcessor();
        log.info("Software.com: Finished initializing SoftwareCo plugin");

        SoftwareCoUtils.setStatusLineMessage(
                "Software.com", "Click to see more from Software.com");

        // run the initial calls in 5 seconds
        new Thread(() -> {
            try {
                Thread.sleep(1000 * 10);
                initializeCalls();
            }
            catch (Exception e){
                System.err.println(e);
            }
        }).start();

        // run the kpm fetch task every minute
        kpmFetchTimer = new Timer();
        kpmFetchTimer.scheduleAtFixedRate(new ProcessKpmSessionInfoTask(), 60 * 1000, 60 * 1000);

        READY = true;
    }

    private void initializeCalls() {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {
                sessionMgr.checkUserAuthenticationStatus();
                sessionMgr.sendOfflineData();
                sessionMgr.fetchDailyKpmSessionInfo();
            }
        });
    }

    private class ProcessKpmSessionInfoTask extends TimerTask {
        public void run() {
            ApplicationManager.getApplication().invokeLater(new Runnable() {
                public void run() {
                    sessionMgr.fetchDailyKpmSessionInfo();
                }
            });
        }
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
        updateFileInfoValue(fileInfo,"open", 1);
        log.info("Software.com: file opened: " + fileName);

        // update the line count since we're here
        int lines = getLineCount(fileName);
        updateFileInfoValue(fileInfo, "lines", lines);
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
        updateFileInfoValue(fileInfo,"close", 1);
        log.info("Software.com: file closed: " + fileName);
    }

    public static String getUserHomeDir() {
        return System.getProperty("user.home");
    }

    public static boolean isWindows() {
        return SystemInfo.isWindows;
    }

    public static boolean isMac() {
        return SystemInfo.isMac;
    }

    protected static int getLineCount(String fileName) {
        Path path = Paths.get(fileName);
        try {
            return (int) Files.lines(path).count();
        } catch (IOException e) {
            log.info("Software.com: failed to get the line count for file " + fileName);
            return 0;
        }
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

        boolean isNewLine = false;
        String newFrag = documentEvent.getNewFragment().toString();
        if (newFrag.matches("^\n.*") || newFrag.matches("^\n\r.*")) {
            isNewLine = true;
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

        if (file == null || file.isDirectory()) {
            return;
        }

        Project project = editors[0].getProject();
        if (project != null) {
            projectName = project.getName();
            projectFilepath = project.getBaseDir().getPath();
        }

        keystrokeMgr.addKeystrokeWrapperIfNoneExists(project);

        if (file != null) {
            fileName = file.getPath();
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

        // check if there are any keystrokes before updating the metrics
        if (numKeystrokes == 0) {
            return;
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

        String currentTrack = SoftwareCoUtils.getCurrentMusicTrack();
        String trackInfo = fileInfo.get("trackInfo").getAsString();
        if ((trackInfo == null || trackInfo.equals("")) && (currentTrack != null && !currentTrack.equals(""))) {
            updateFileInfoStringValue(fileInfo, "trackInfo", currentTrack);
        }

        if (wrapper.getKeystrokeCount() != null && wrapper.getKeystrokeCount().getProject() != null
                && !wrapper.getKeystrokeCount().getProject().hasResource() ) {
            JsonObject resource = SoftwareCoUtils.getResourceInfo(projectFilepath);
            if (resource.has("identifier")) {
                wrapper.getKeystrokeCount().getProject().updateResource(resource);
                wrapper.getKeystrokeCount().getProject().setIdentifier(resource.get("identifier").getAsString());
            }
        }
        SoftwareCoUtils.getResourceInfo(projectFilepath);

        if (numKeystrokes > 1 && !isNewLine) {
            // It's a copy and paste event
            updateFileInfoValue(fileInfo,"paste", numKeystrokes);

            log.info("Software.com: Copy+Paste incremented");
        } else if (numKeystrokes < 0) {
            int deleteKpm = Math.abs(numKeystrokes);
            // It's a character delete event
            updateFileInfoValue(fileInfo,"delete", deleteKpm);

            log.info("Software.com: Delete incremented");
        } else if (!isNewLine) {
            // increment the specific file keystroke value
            updateFileInfoValue(fileInfo,"add", 1);

            log.info("Software.com: KPM incremented");
        }

        int incrementedCount = Integer.parseInt(keystrokeCount.getData()) + 1;
        keystrokeCount.setData( String.valueOf(incrementedCount) );

        // update the line count
        int lines = getPreviousLineCount(fileInfo);
        if (lines == -1) {
            lines = getLineCount(fileName);
        }

        if (isNewLine) {
            lines += 1;
            // new lines added
            updateFileInfoValue(fileInfo, "linesAdded", 1);
            log.info("Software.com: lines added incremented");
        }

        updateFileInfoValue(fileInfo, "lines", lines);
        updateFileInfoValue(fileInfo,"length", currLen);
    }

    private static int getPreviousLineCount(JsonObject fileInfo) {
        JsonPrimitive keysVal = fileInfo.getAsJsonPrimitive("lines");
        return keysVal.getAsInt();
    }

    private static void updateFileInfoStringValue(JsonObject fileInfo, String key, String value) {
        fileInfo.addProperty(key, value);
    }

    private static void updateFileInfoValue(JsonObject fileInfo, String key, int incrementVal) {
        JsonPrimitive keysVal = fileInfo.getAsJsonPrimitive(key);
        if (key.equals("length") || key.equals("lines")) {
            // length, lines, or syntax are not additive
            fileInfo.addProperty(key, incrementVal);
        } else {
            int totalVal = keysVal.getAsInt() + incrementVal;
            fileInfo.addProperty(key, totalVal);
        }

        if (key.equals("add") || key.equals("delete")) {
            // update the netkeys and the keys
            // "netkeys" = add - delete
            // "keys" = add + delete
            int deleteCount = fileInfo.get("delete").getAsInt();
            int addCount = fileInfo.get("add").getAsInt();
            fileInfo.addProperty("keys", (addCount + deleteCount));
            fileInfo.addProperty("netkeys", (addCount - deleteCount));
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

    private void processKeystrokes() {
        if (READY) {

            List<KeystrokeManager.KeystrokeCountWrapper> wrappers = keystrokeMgr.getKeystrokeCountWrapperList();
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

    private void sendKeystrokeData(KeystrokeCount keystrokeCount) {

        if (!SoftwareCo.TELEMTRY_ON) {
            log.info("Software.com telemetry is currently paused. Enable to view KPM metrics");
            String payload = SoftwareCo.gson.toJson(keystrokeCount);
            sessionMgr.storePayload(payload);
            return;
        }

        KeystrokeDataSendTask sendTask = new KeystrokeDataSendTask(keystrokeCount);
        Future<HttpResponse> response = SoftwareCoUtils.executorService.submit(sendTask);

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

        if (postFailed) {
            log.info("Saving kpm info offline");
            // save the data offline
            String payload = SoftwareCo.gson.toJson(keystrokeCount);
            sessionMgr.storePayload(payload);
            sessionMgr.checkUserAuthenticationStatus();
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
            String kpmData = gson.toJson(keystrokeCount);

            String endpoint = SoftwareCoUtils.api_endpoint + "/data";

            try {
                HttpPost request = new HttpPost(endpoint);
                StringEntity params = new StringEntity(kpmData);
                request.addHeader("Content-type", "application/json");

                // add the auth token
                String jwtToken = SoftwareCoSessionManager.getItem("jwt");
                // we need the header, but check if it's null anyway
                if (jwtToken != null) {
                    request.addHeader("Authorization", jwtToken);
                }

                request.setEntity(params);

                //
                // Send the POST request
                //
                SoftwareCoUtils.logApiRequest(request, kpmData);
                HttpResponse response = SoftwareCoUtils.httpClient.execute(request);

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