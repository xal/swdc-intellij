/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import org.apache.commons.lang.SystemUtils;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.TimeZone;
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

    private final String LEGACY_VIM_ID = "0q9p7n6m4k2j1VIM54t";

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
    private Timer userStatusTimer;

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

        long one_min = 1000 * 60;
        long ninety_sec = one_min + (1000 * 30);
        long one_hour = one_min * 60;

        ProcessKpmSessionInfoTask kpmTask = new ProcessKpmSessionInfoTask();

        // run the kpm fetch task every minute
        kpmFetchTimer = new Timer();
        kpmFetchTimer.scheduleAtFixedRate(
                kpmTask, 1000 * 20, one_min);

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

        userStatusTimer = new Timer();
        userStatusTimer.scheduleAtFixedRate(
                new ProcessUserStatusTask(), one_min, ninety_sec);

        eventMgr.setAppIsReady(true);

        this.handleMigrationUpdates();

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
                if (requiresUserCreation()) {
                    createAnonymousUser();
                }

                SoftwareCoUtils.UserStatus userStatus = SoftwareCoUtils.getUserStatus();
                if (userStatus.loggedInUser == null) {
                    // ask the user to login one time only
                    // run the initial calls in 6 seconds
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000 * 10);
                            sessionMgr.checkUserAuthenticationStatus();
                        }
                        catch (Exception e){
                            System.err.println(e);
                        }
                    }).start();
                    initializeCalls();
                }
            }
        });
    }

    protected String getAppJwt() {
        String appJwt = SoftwareCoSessionManager.getItem("app_jwt");
        boolean serverIsOnline = SoftwareCoSessionManager.isServerOnline();
        if (appJwt == null && serverIsOnline) {
            String macAddress = SoftwareCoUtils.getMacAddress();
            if (macAddress != null) {
                String encodedMacIdentity = "";
                try {
                    encodedMacIdentity = URLEncoder.encode(macAddress, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    // url encoding failed, just use the mac addr id
                    encodedMacIdentity = macAddress;
                }

                String api = "/data/token?addr=" + encodedMacIdentity;
                SoftwareResponse resp = SoftwareCoUtils.makeApiCall(api, HttpGet.METHOD_NAME, null);
                if (resp.isOk()) {
                    JsonObject obj = resp.getJsonObj();
                    appJwt = obj.get("jwt").getAsString();
                    SoftwareCoSessionManager.setItem("app_jwt", appJwt);
                }
            }
        }
        return SoftwareCoSessionManager.getItem("app_jwt");
    }

    protected boolean requiresUserCreation() {
        // check using the mac address
        List<SoftwareCoUtils.User> authAccounts = SoftwareCoUtils.getAuthenticatedPluginAccounts();
        if (authAccounts != null && authAccounts.size() > 0) {
            for (SoftwareCoUtils.User user : authAccounts) {
                if (user.email != null && user.mac_addr != null && user.email.equals(user.mac_addr)) {
                    return false;
                }
            }
        }
        return true;
    }

    protected void createAnonymousUser() {
        boolean serverIsOnline = SoftwareCoSessionManager.isServerOnline();
        String pluginToken = SoftwareCoSessionManager.getItem("token");
        String macAddress = SoftwareCoUtils.getMacAddress();
        // make sure we've fetched the app jwt
        String appJwt = getAppJwt();

        if (serverIsOnline && macAddress != null) {
            String email = macAddress;
            if (pluginToken == null) {
                pluginToken = SoftwareCoSessionManager.generateToken();
                SoftwareCoSessionManager.setItem("token", pluginToken);
            }
            String timezone = TimeZone.getDefault().getID();

            String encodedMacIdentity = "";
            try {
                encodedMacIdentity = URLEncoder.encode(macAddress, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // url encoding failed, just use the mac addr id
                encodedMacIdentity = macAddress;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("email", email);
            payload.addProperty("plugin_token", pluginToken);
            payload.addProperty("timezone", timezone);
            String api = "/data/onboard?addr=" + encodedMacIdentity;
            SoftwareResponse resp = SoftwareCoUtils.makeApiCall(api, HttpPost.METHOD_NAME, payload.toString(), appJwt);
            if (resp.isOk()) {
                // check if we have the data and jwt
                // resp.data.jwt and resp.data.user
                // then update the session.json for the jwt, user, and jetbrains_lastUpdateTime
                JsonObject data = resp.getJsonObj();
                // check if we have any data
                if (data != null && data.has("jwt")) {
                    String dataJwt = data.get("jwt").getAsString();
                    String user = data.get("user").getAsString();
                    SoftwareCoSessionManager.setItem("jwt", dataJwt);
                    SoftwareCoSessionManager.setItem("user", user);
                }
            }
        }
    }

    private void handleMigrationUpdates() {
        String tokenVal = SoftwareCoSessionManager.getItem("token");
        // vim plugin id check
        if (tokenVal != null && tokenVal.equals(LEGACY_VIM_ID)) {
            // delete the session json to re-establish a handshake without the vim token id
            String sessionJsonFile = SoftwareCoSessionManager.getSoftwareSessionFile();
            sessionMgr.deleteFile(sessionJsonFile);
        }
    }

    private void initializeCalls() {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {
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

    private class ProcessUserStatusTask extends TimerTask {
        public void run() {
            ApplicationManager.getApplication().invokeLater(new Runnable() {
                @Override
                public void run() {
                    SoftwareCoUtils.getUserStatus();
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

    public static String getOsUserName() {
        return System.getProperty("user.name");
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

    public static boolean isWindows() {
        return SystemInfo.isWindows;
    }

    public static boolean isMac() {
        return SystemInfo.isMac;
    }



}