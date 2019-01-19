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
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Intellij Plugin Application
 * ....
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
    private SoftwareCoMusicManager musicMgr = SoftwareCoMusicManager.getInstance();

    private Timer kpmFetchTimer;
    private Timer trackInfoTimer;
    private Timer repoInfoTimer;
    private Timer repoCommitsTimer;

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

        // run the initial calls in 6 seconds
        new Thread(() -> {
            try {
                Thread.sleep(1000 * 6);
                initializeCalls();
            }
            catch (Exception e){
                System.err.println(e);
            }
        }).start();

        long one_min = 1000 * 60;
        long one_hour = one_min * 60;

        // run the kpm fetch task every minute
        kpmFetchTimer = new Timer();
        kpmFetchTimer.scheduleAtFixedRate(
                new ProcessKpmSessionInfoTask(), one_min, one_min);

        // run the music manager task every 15 seconds
        trackInfoTimer = new Timer();
        trackInfoTimer.scheduleAtFixedRate(
                new ProcessMusicTrackInfoTask(), one_min, 15 * 1000);

        repoInfoTimer = new Timer();
        repoInfoTimer.scheduleAtFixedRate(
                new ProcessRepoInfoTask(), one_min * 2, one_hour);

        repoCommitsTimer = new Timer();
        repoCommitsTimer.scheduleAtFixedRate(
                new ProcessRepoCommitsTask(), one_min * 3, one_hour + one_min);

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

    private class ProcessMusicTrackInfoTask extends TimerTask {
        public void run() {
            ApplicationManager.getApplication().invokeLater(new Runnable() {
                public void run() {
                    musicMgr.processMusicTrackInfo();
                }
            });
        }
    }

    private class ProcessRepoInfoTask extends TimerTask {
        public void run() {
            ApplicationManager.getApplication().invokeLater(new Runnable() {
                public void run() {
                    SoftwareCoRepoManager.getInstance().processRepoMembersInfo(getRootPath());
                }
            });
        }
    }

    private class ProcessRepoCommitsTask extends TimerTask {
        public void run() {
            ApplicationManager.getApplication().invokeLater(new Runnable() {
                @Override
                public void run() {
                    SoftwareCoRepoManager.getInstance().getHistoricalCommits(getRootPath());
                }
            });
        }
    }

    protected String getRootPath() {
        Editor[] editors = EditorFactory.getInstance().getAllEditors();
        if (editors != null && editors.length > 0) {
            for (Editor editor : editors) {
                Project project = editor.getProject();
                if (project != null && project.getBaseDir() != null) {
                    return project.getBaseDir().getPath();
                }
            }
        }
        return null;
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

                SoftwareCoUtils.setStatusLineMessage(
                        "Software.com", "Click to see more from Software.com");
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
        FileDocumentManager instance = FileDocumentManager.getInstance();
        if (instance != null) {
            VirtualFile file = instance.getFile(document);
            if (file != null && !file.isDirectory()) {
                Editor[] editors = EditorFactory.getInstance().getEditors(document);
                if (editors != null && editors.length > 0) {
                    String fileName = file.getPath();
                    Project project = editors[0].getProject();
                    String projectName = null;
                    String projectFilepath = null;
                    if (project != null) {
                        projectName = project.getName();
                        projectFilepath = project.getBaseDir().getPath();

                        keystrokeMgr.addKeystrokeWrapperIfNoneExists(project);
                        initializeKeystrokeObjectGraph(fileName, projectName, projectFilepath);

                        KeystrokeCount keystrokeCount = keystrokeMgr.getKeystrokeCount(projectName);
                        if (keystrokeCount != null) {

                            KeystrokeManager.KeystrokeCountWrapper wrapper = keystrokeMgr.getKeystrokeWrapper(projectName);


                            // Set the current text length and the current file and the current project
                            //
                            int currLen = document.getTextLength();
                            wrapper.setCurrentFileName(fileName);
                            wrapper.setCurrentTextLength(currLen);

                            JsonObject fileInfo = keystrokeCount.getSourceByFileName(fileName);
                            if (documentEvent.getOldLength() > 0) {
                                //it's a delete
                                updateFileInfoValue(fileInfo, "delete", 1);
                            } else {
                                // it's an add
                                if (documentEvent.getNewLength() > 1) {
                                    // it's a paste
                                    updateFileInfoValue(fileInfo, "paste", 1);
                                } else {
                                    updateFileInfoValue(fileInfo, "add", 1);
                                }
                            }

                            int incrementedCount = Integer.parseInt(keystrokeCount.getKeystrokes()) + 1;
                            keystrokeCount.setKeystrokes(String.valueOf(incrementedCount));

                            String newFrag = documentEvent.getNewFragment().toString();
                            if (newFrag.matches("^\n.*") || newFrag.matches("^\n\r.*")) {
                                // it's a new line
                                updateFileInfoValue(fileInfo, "linesAdded", 1);
                            }
                            updateFileInfoValue(fileInfo, "lines", getLineCount(fileName));
                        }
                    }
                }

            }
        }
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
            int deleteCount = fileInfo.get("delete").getAsInt();
            int addCount = fileInfo.get("add").getAsInt();
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
                    // ZonedDateTime will get us the true seconds away from GMT
                    // it'll be negative for zones before GMT and postive for zones after
                    Integer offset  = ZonedDateTime.now().getOffset().getTotalSeconds();
                    long startInSeconds = (int) (new Date().getTime() / 1000);
                    wrapper.getKeystrokeCount().setStart(startInSeconds);
                    // add to the start in seconds since it's a negative for less than gmt and the
                    // opposite for grtr than gmt
                    wrapper.getKeystrokeCount().setLocal_start(startInSeconds + offset);
                    wrapper.getKeystrokeCount().setTimezone(TimeZone.getDefault().getID());
                    String payload = SoftwareCo.gson.toJson(wrapper.getKeystrokeCount());

                    if (!SoftwareCo.TELEMTRY_ON) {
                        log.info("Software.com telemetry is currently paused. Enable to view KPM metrics");
                        sessionMgr.storePayload(payload);
                        continue;
                    }

                    SoftwareResponse resp = SoftwareCoUtils.makeApiCall("/data", HttpPost.METHOD_NAME, payload);
                    if (!resp.isOk() && !SoftwareCoSessionManager.isDeactivated()) {
                        sessionMgr.storePayload(payload);
                        sessionMgr.checkUserAuthenticationStatus();
                    }

                    keystrokeMgr.resetData(wrapper.getKeystrokeCount().getProject().getName());
                }
            }
        }
    }
}