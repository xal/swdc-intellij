/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.messages.MessageBusConnection;
import org.apache.log4j.Level;


/**
 * Intellij Plugin Application
 * ....
 */
public class SoftwareCo implements ApplicationComponent {

    public static JsonParser jsonParser = new JsonParser();
    public static final Logger log = Logger.getInstance("SoftwareCo");
    public static Gson gson;

    private MessageBusConnection[] connections;
    public static MessageBusConnection connection;


    private SoftwareCoMusicManager musicMgr = SoftwareCoMusicManager.getInstance();
    private SoftwareCoSessionManager sessionMgr = SoftwareCoSessionManager.getInstance();
    private SoftwareCoEventManager eventMgr = SoftwareCoEventManager.getInstance();
    private AsyncManager asyncManager = AsyncManager.getInstance();

    private static int retry_counter = 0;
    private static long check_online_interval_ms = 1000 * 60 * 10;


    public SoftwareCo() {
    }

    public void initComponent() {
        boolean serverIsOnline = SoftwareCoSessionManager.isServerOnline();
        boolean sessionFileExists = SoftwareCoSessionManager.softwareSessionFileExists();
        if (!sessionFileExists) {
            if (!serverIsOnline) {
                // server isn't online, check again in 10 min
                if (retry_counter == 0) {
                    showOfflinePrompt();
                }
                new Thread(() -> {
                    try {
                        Thread.sleep(check_online_interval_ms);
                        initComponent();
                    } catch (Exception e) {
                        System.err.println(e);
                    }
                }).start();
            } else {
                // create the anon user
                String jwt = SoftwareCoUtils.createAnonymousUser(serverIsOnline);
                if (jwt == null) {
                    // it failed, try again later
                    if (retry_counter == 0) {
                        showOfflinePrompt();
                    }
                    new Thread(() -> {
                        try {
                            Thread.sleep(check_online_interval_ms);
                            initComponent();
                        } catch (Exception e) {
                            System.err.println(e);
                        }
                    }).start();
                } else {
                    initializePlugin(true);
                }
            }
        } else {
            // session json already exists, continue with plugin init
            initializePlugin(false);
        }
    }

    protected void initializePlugin(boolean initializedUser) {

        log.info("Code Time: Loaded v" + SoftwareCoUtils.getVersion());

        setLoggingLevel();

        gson = new Gson();

        setupEventListeners();

        // add the kpm payload one_min scheduler
        final Runnable payloadPushRunner = () -> eventMgr.processKeystrokesData();
        asyncManager.scheduleService(
                payloadPushRunner, "payloadPushRunner", 60, 60);

        log.info("Code Time: Finished initializing SoftwareCo plugin");

        // add the kpm status scheduler
        final Runnable kpmStatusRunner = () -> sessionMgr.fetchDailyKpmSessionInfo();
        asyncManager.scheduleService(
                kpmStatusRunner, "kpmStatusRunner", 15, 60);

        final Runnable hourlyRunner = () -> this.processHourlyJobs();
        asyncManager.scheduleService(
                hourlyRunner, "musicTrackRunner", 45, 60 * 60);

        // run the music manager task every 15 seconds
        final Runnable musicTrackRunner = () -> musicMgr.processMusicTrackInfo();
        asyncManager.scheduleService(
                musicTrackRunner, "musicTrackRunner", 30, 15);

        final Runnable userStatusRunner = () -> SoftwareCoUtils.getUserStatus();
        asyncManager.scheduleService(
                userStatusRunner, "userStatusRunner", 60, 90);

        eventMgr.setAppIsReady(true);

        new Thread(() -> {
            try {
                Thread.sleep(5000);
                initializeUserInfo(initializedUser);
            } catch (Exception e) {
                System.err.println(e);
            }
        }).start();

    }

    private void processHourlyJobs() {
        SoftwareCoUtils.sendHeartbeat("HOURLY");

        SoftwareCoRepoManager repoMgr = SoftwareCoRepoManager.getInstance();
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                repoMgr.processRepoMembersInfo(getRootPath());
            } catch (Exception e) {
                System.err.println(e);
            }
        }).start();
        new Thread(() -> {
            try {
                Thread.sleep(60000);
                repoMgr.getHistoricalCommits(getRootPath());
            } catch (Exception e) {
                System.err.println(e);
            }
        }).start();
    }

    private void initializeUserInfo(boolean initializedUser) {

        SoftwareCoUtils.getUserStatus();

        if (initializedUser) {
            // send an initial plugin payload
            this.sendInstallPayload();

            // ask the user to login one time only
            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    sessionMgr.showLoginPrompt();
                }
                catch (Exception e){
                    System.err.println(e);
                }
            }).start();
        }

        new Thread(() -> {
            try {
                Thread.sleep(1000 * 10);
                sessionMgr.fetchDailyKpmSessionInfo();
            }
            catch (Exception e){
                System.err.println(e);
            }
        }).start();

        SoftwareCoUtils.sendHeartbeat("INITIALIZED");
    }

    protected void sendInstallPayload() {
        KeystrokeManager keystrokeManager = KeystrokeManager.getInstance();
        String fileName = "Untitled";
        eventMgr.initializeKeystrokeObjectGraph(fileName, "Unnamed", "");
        JsonObject fileInfo = keystrokeManager.getKeystrokeCount().getSourceByFileName(fileName);
        eventMgr.updateFileInfoValue(fileInfo, "add", 1);
        keystrokeManager.getKeystrokeCount().setKeystrokes(String.valueOf(1));
        eventMgr.processKeystrokes(keystrokeManager.getKeystrokeWrapper());
    }

    protected String getRootPath() {
        Editor[] editors = EditorFactory.getInstance().getAllEditors();
        if (editors != null && editors.length > 0) {
            for (Editor editor : editors) {
                Project project = editor.getProject();
                if (project != null && project.getBasePath() != null) {
                    String filePath = project.getBasePath();
                    return filePath;
                }
            }
        }
        return null;
    }

    private void setupEventListeners() {
        ApplicationManager.getApplication().invokeLater(new Runnable(){
            public void run() {

                // save file
//                MessageBus bus = ApplicationManager.getApplication().getMessageBus();
//                connection = bus.connect();
//                connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new SoftwareCoFileEditorListener());

                // edit document
                EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new SoftwareCoDocumentListener());
            }
        });


//        Project[] projects = ProjectManager.getInstance().getOpenProjects();
//        if (projects != null) {
//            connections = new MessageBusConnection[projects.length];
//            for (int i = 0; i < projects.length; i++) {
//                Project project = projects[i];
//                MessageBusConnection connection = project.getMessageBus().connect(project);
//                connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new SoftwareCoFileEditorListener());
//                connections[i] = connection;
//            }
//        }

        SoftwareCoUtils.setStatusLineMessage(
                "Code Time", "Click to see more from Code Time");
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

        asyncManager.destroyServices();

        // process one last time
        // this will ensure we process the latest keystroke updates
        eventMgr.processKeystrokesData();
    }

    public static void setLoggingLevel() {
        log.setLevel(Level.INFO);
    }

    protected void showOfflinePrompt() {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {
                String infoMsg = "Our service is temporarily unavailable. We will try to reconnect again " +
                        "in 10 minutes. Your status bar will not update at this time.";
                // ask to download the PM
                Messages.showInfoMessage(infoMsg, "Code Time");
            }
        });
    }

}