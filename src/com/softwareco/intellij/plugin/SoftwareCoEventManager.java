package com.softwareco.intellij.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.apache.http.client.methods.HttpPost;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.TimeZone;
import java.util.stream.Stream;

public class SoftwareCoEventManager {

    public static final Logger log = Logger.getInstance("SoftwareCoEventManager");

    private static SoftwareCoEventManager instance = null;

    private KeystrokeManager keystrokeMgr = KeystrokeManager.getInstance();
    private SoftwareCoSessionManager sessionMgr = SoftwareCoSessionManager.getInstance();
    private boolean appIsReady = false;

    public static SoftwareCoEventManager getInstance() {
        if (instance == null) {
            instance = new SoftwareCoEventManager();
        }
        return instance;
    }

    public void setAppIsReady(boolean appIsReady) {
        this.appIsReady = appIsReady;
    }

    public void handleFileOpenedEvents(String fileName, Project project) {
        KeystrokeCount keystrokeCount = keystrokeMgr.getKeystrokeCount();
        if (keystrokeCount == null) {
            initializeKeystrokeObjectGraph(fileName, project.getName(), project.getProjectFilePath());
            keystrokeCount = keystrokeMgr.getKeystrokeCount();
        }
        JsonObject fileInfo = keystrokeCount.getSourceByFileName(fileName);
        if (fileInfo == null) {
            return;
        }
        updateFileInfoValue(fileInfo,"open", 1);
        log.info("Code Time: file opened: " + fileName);

        // update the line count since we're here
        int lines = getLineCount(fileName);
        updateFileInfoValue(fileInfo, "lines", lines);
    }

    public void handleFileClosedEvents(String fileName, Project project) {
        KeystrokeCount keystrokeCount = keystrokeMgr.getKeystrokeCount();
        if (keystrokeCount == null) {
            initializeKeystrokeObjectGraph(fileName, project.getName(), project.getProjectFilePath());
            keystrokeCount = keystrokeMgr.getKeystrokeCount();
        }
        JsonObject fileInfo = keystrokeCount.getSourceByFileName(fileName);
        if (fileInfo == null) {
            return;
        }
        updateFileInfoValue(fileInfo,"close", 1);
        log.info("Code Time: file closed: " + fileName);
    }

    protected int getLineCount(String fileName) {
        Path path = Paths.get(fileName);
        try {
            Stream<String> stream = Files.lines(path);
            int count = (int) stream.count();
            stream.close();
            return count;

        } catch (Exception e) {
            log.error("Code Time: unable to get the line count for file " + fileName);
            return 0;
        } catch (Throwable e) {
            log.error("Code Time: unable to get the line count for file " + fileName);
            return 0;
        }
    }

    /**
     * Handles character change events in a file
     * @param document
     * @param documentEvent
     */
    public void handleChangeEvents(Document document, DocumentEvent documentEvent) {

        if (document == null) {
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
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
                            projectFilepath = project.getBasePath();

                            keystrokeMgr.addKeystrokeWrapperIfNoneExists(project);
                            initializeKeystrokeObjectGraph(fileName, projectName, projectFilepath);

                            KeystrokeCount keystrokeCount = keystrokeMgr.getKeystrokeCount();
                            if (keystrokeCount != null) {

                                KeystrokeManager.KeystrokeCountWrapper wrapper = keystrokeMgr.getKeystrokeWrapper();


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
                                int documentLineCount = document.getLineCount();
                                updateFileInfoValue(fileInfo, "lines", documentLineCount);
                            }
                        }
                    }

                }
            }
        });
    }

    public void updateFileInfoValue(JsonObject fileInfo, String key, int incrementVal) {
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

    public void initializeKeystrokeObjectGraph(String fileName, String projectName, String projectFilepath) {
        // initialize it in case it's not initialized yet
        initializeKeystrokeCount(projectName, projectFilepath);

        KeystrokeCount keystrokeCount = keystrokeMgr.getKeystrokeCount();

        //
        // Make sure we have the project name and directory info
        updateKeystrokeProject(projectName, fileName, keystrokeCount);
    }

    private void initializeKeystrokeCount(String projectName, String projectFilepath) {
        KeystrokeCount keystrokeCount = keystrokeMgr.getKeystrokeCount();
        if ( keystrokeCount == null || keystrokeCount.getProject() == null ) {
            createKeystrokeCountWrapper(projectName, projectFilepath);
        } else if (!keystrokeCount.getProject().getName().equals(projectName)) {
            final KeystrokeManager.KeystrokeCountWrapper current = keystrokeMgr.getKeystrokeWrapper();

            // send the current wrapper and create a new one
            final Runnable processKpmRunner = () -> this.processKeystrokes(current);
            processKpmRunner.run();

            createKeystrokeCountWrapper(projectName, projectFilepath);
        }
    }

    private void createKeystrokeCountWrapper(String projectName, String projectFilepath) {
        //
        // Create one since it hasn't been created yet
        // and set the start time (in seconds)
        //
        KeystrokeCount keystrokeCount = new KeystrokeCount();

        KeystrokeProject keystrokeProject = new KeystrokeProject( projectName, projectFilepath );
        keystrokeCount.setProject( keystrokeProject );

        //
        // Update the manager with the newly created KeystrokeCount object
        //
        keystrokeMgr.setKeystrokeCount(projectName, keystrokeCount);
    }

    private void updateKeystrokeProject(String projectName, String fileName, KeystrokeCount keystrokeCount) {
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

    private String getProjectDirectory(String projectName, String fileName) {
        String projectDirectory = "";
        if ( projectName != null && projectName.length() > 0 &&
                fileName != null && fileName.length() > 0 &&
                fileName.indexOf(projectName) > 0 ) {
            projectDirectory = fileName.substring( 0, fileName.indexOf( projectName ) - 1 );
        }
        return projectDirectory;
    }

    public void processKeystrokesData() {
        final KeystrokeManager.KeystrokeCountWrapper current = keystrokeMgr.getKeystrokeWrapper();
        // send the current wrapper and create a new one
        processKeystrokes(current);
    }

    public void processKeystrokes(KeystrokeManager.KeystrokeCountWrapper wrapper) {
        if (appIsReady) {

            // send any offline data if we have any
            sessionMgr.sendOfflineData();

            if (wrapper != null && wrapper.getKeystrokeCount() != null && wrapper.getKeystrokeCount().hasData()) {
                // ZonedDateTime will get us the true seconds away from GMT
                // it'll be negative for zones before GMT and postive for zones after
                Integer offset  = ZonedDateTime.now().getOffset().getTotalSeconds();
                long startInSeconds = (int) (new Date().getTime() / 1000);
                wrapper.getKeystrokeCount().setStart(startInSeconds);
                // add to the start in seconds since it's a negative for less than gmt and the
                // opposite for grtr than gmt
                wrapper.getKeystrokeCount().setLocal_start(startInSeconds + offset);
                wrapper.getKeystrokeCount().setTimezone(TimeZone.getDefault().getID());
                final String payload = SoftwareCo.gson.toJson(wrapper.getKeystrokeCount());

                SoftwareResponse resp = SoftwareCoUtils.makeApiCall("/data", HttpPost.METHOD_NAME, payload);
                if (!resp.isOk()) {
                    sessionMgr.storePayload(payload);
                }

                keystrokeMgr.resetData();
            } else if (wrapper != null && wrapper.getKeystrokeCount() != null) {
                keystrokeMgr.resetData();
            }

        }
    }
}
