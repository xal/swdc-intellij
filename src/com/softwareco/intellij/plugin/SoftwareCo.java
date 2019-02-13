/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.application.ApplicationInfo;
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
import com.intellij.util.PlatformUtils;
import com.intellij.util.messages.MessageBusConnection;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;


import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

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

    private SoftwareCoMusicManager musicMgr = SoftwareCoMusicManager.getInstance();
    private KeystrokeManager keystrokeMgr = KeystrokeManager.getInstance();
    private SoftwareCoSessionManager sessionMgr = SoftwareCoSessionManager.getInstance();
    private SoftwareCoEventManager eventMgr = SoftwareCoEventManager.getInstance();

    private Timer kpmFetchTimer;
    private Timer trackInfoTimer;
    private Timer repoInfoTimer;
    private Timer repoCommitsTimer;

    public SoftwareCo() {
    }

    public void initComponent() {

        IdeaPluginDescriptor pluginDescriptor = PluginManager.getPlugin(PluginId.getId("com.softwareco.intellij.plugin"));
        VERSION = pluginDescriptor.getVersion();
        log.info("Code Time: Loaded v" + VERSION);

        // Set runtime constants
        IDE_NAME = PlatformUtils.getPlatformPrefix();
        IDE_VERSION = ApplicationInfo.getInstance().getFullVersion();

        setLoggingLevel();

        gson = new Gson();

        setupEventListeners();
        setupScheduledProcessor();
        log.info("Code Time: Finished initializing SoftwareCo plugin");

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

        eventMgr.setAppIsReady(true);
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
                    }
                }

                SoftwareCoUtils.setStatusLineMessage(
                        "Code Time", "Click to see more from Code Time");
            }
        });
    }

    private void setupScheduledProcessor() {
        final Runnable handler = () -> eventMgr.processKeystrokesData();
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

    public static boolean isWindows() {
        return SystemInfo.isWindows;
    }

    public static boolean isMac() {
        return SystemInfo.isMac;
    }



}