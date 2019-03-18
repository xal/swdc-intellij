/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.messages.MessageBusConnection;
import org.apache.commons.lang.SystemUtils;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;


/**
 * Intellij Plugin Application
 * ....
 */
public class SoftwareCo implements ApplicationComponent {

    public static JsonParser jsonParser = new JsonParser();
    public static final Logger log = Logger.getInstance("SoftwareCo");
    public static Gson gson;

    private String VERSION;
    private MessageBusConnection[] connections;


    private SoftwareCoMusicManager musicMgr = SoftwareCoMusicManager.getInstance();
    private SoftwareCoSessionManager sessionMgr = SoftwareCoSessionManager.getInstance();
    private SoftwareCoEventManager eventMgr = SoftwareCoEventManager.getInstance();
    private AsyncManager asyncManager = AsyncManager.getInstance();


    public SoftwareCo() {
    }

    public void initComponent() {

        IdeaPluginDescriptor pluginDescriptor = PluginManager.getPlugin(PluginId.getId("com.softwareco.intellij.plugin"));
        VERSION = pluginDescriptor.getVersion();
        log.info("Code Time: Loaded v" + VERSION);

        setLoggingLevel();

        gson = new Gson();

        setupEventListeners();

        SoftwareCoRepoManager repoMgr = SoftwareCoRepoManager.getInstance();


        // add the kpm payload one_min scheduler
        final Runnable payloadPushRunner = () -> eventMgr.processKeystrokesData();
        asyncManager.scheduleService(
                payloadPushRunner, "payloadPushRunner", 60, 60);

        log.info("Code Time: Finished initializing SoftwareCo plugin");

        int one_hour_in_sec = 60 * 60;
        int one_hour_ten_min_in_sec = one_hour_in_sec + (60 * 10);

        // add the kpm status scheduler
        final Runnable kpmStatusRunner = () -> sessionMgr.fetchDailyKpmSessionInfo();
        asyncManager.scheduleService(
                kpmStatusRunner, "kpmStatusRunner", 15, 60);

        // run the music manager task every 15 seconds
        final Runnable musicTrackRunner = () -> musicMgr.processMusicTrackInfo();
        asyncManager.scheduleService(
                musicTrackRunner, "musicTrackRunner", 30, 15);

        // run the repo info task every 1 hour
        final Runnable repoInfoRunner = () -> repoMgr.processRepoMembersInfo(getRootPath());
        asyncManager.scheduleService(
                repoInfoRunner, "repoInfoRunner", 90, one_hour_in_sec);

        // run the repo commits task every 2 hours
        final Runnable repoCommitsRunner = () -> repoMgr.getHistoricalCommits(getRootPath());
        asyncManager.scheduleService(
                repoCommitsRunner, "repoCommitsRunner", 120, one_hour_ten_min_in_sec);

        final Runnable userStatusRunner = () -> SoftwareCoUtils.getUserStatus();
        asyncManager.scheduleService(
                userStatusRunner, "userStatusRunner", 60, 90);

        eventMgr.setAppIsReady(true);

        new Thread(() -> {
            try {
                Thread.sleep(5000);
                initializeUserInfo();
            } catch (Exception e) {
                System.err.println(e);
            }
        }).start();

    }

    private void initializeUserInfo() {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {
                // this should only ever possibly return true the very first
                // time the IDE loads this new code

                String jwt = SoftwareCoSessionManager.getItem("jwt");
                boolean initializingPlugin = false;
                if (jwt == null || jwt.equals("")) {
                    initializingPlugin = true;
                }

                SoftwareCoUtils.UserStatus userStatus = SoftwareCoUtils.getUserStatus();

                if (initializingPlugin) {
                    // ask the user to login one time only
                    new Thread(() -> {
                        try {
                            Thread.sleep(5000);
                            sessionMgr.checkUserAuthenticationStatus();
                        }
                        catch (Exception e){
                            System.err.println(e);
                        }
                    }).start();
                }

                new Thread(() -> {
                    try {
                        Thread.sleep(1000 * 20);
                        initializeCalls();
                    }
                    catch (Exception e){
                        System.err.println(e);
                    }
                }).start();
            }
        });
    }

    private void initializeCalls() {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {
                sessionMgr.sendOfflineData();
                sessionMgr.fetchDailyKpmSessionInfo();
            }
        });
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
                    }
                }

                SoftwareCoUtils.setStatusLineMessage(
                        "Code Time", "Click to see more from Code Time");
            }
        });
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

    @NotNull
    public String getComponentName() {
        return "SoftwareCo";
    }



    public static String getUserHomeDir() {
        return System.getProperty("user.home");
    }

    public static String getOsInfo() {
        String osName = SystemUtils.OS_NAME;
        String osVersion = SystemUtils.OS_VERSION;
        String osArch = SystemUtils.OS_ARCH;

        String osInfo = "";
        if (osArch != null) {
            osInfo += osArch;
        }
        if (osInfo.length() > 0) {
            osInfo += "_";
        }
        if (osVersion != null) {
            osInfo += osVersion;
        }
        if (osInfo.length() > 0) {
            osInfo += "_";
        }
        if (osName != null) {
            osInfo += osName;
        }

        return osInfo;
    }

    public static boolean isLinux() {
        return (isWindows() || isMac()) ? false : true;
    }

    public static boolean isWindows() {
        return SystemInfo.isWindows;
    }

    public static boolean isMac() {
        return SystemInfo.isMac;
    }



}