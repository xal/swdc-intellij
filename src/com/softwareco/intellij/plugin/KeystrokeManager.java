/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;

public class KeystrokeManager {

    private static KeystrokeManager instance = null;

    List<KeystrokeCountWrapper> keystrokeCountWrapperList = new ArrayList<KeystrokeCountWrapper>();

    /**
     * Protected constructor to defeat instantiation
     */
    protected KeystrokeManager() {
        //
    }

    public static KeystrokeManager getInstance() {
        if (instance == null) {
            instance = new KeystrokeManager();
        }
        return instance;
    }

    public void addKeystrokeWrapperIfNoneExists(Project project) {
        if (this.getKeystrokeWrapper(project.getName()) == null) {
            KeystrokeCountWrapper wrapper = new KeystrokeCountWrapper();
            wrapper.setProjectName(project.getName());
            keystrokeCountWrapperList.add(wrapper);
        }
    }

    public void resetData(String projectName) {
        for (KeystrokeCountWrapper wrapper : keystrokeCountWrapperList) {
            if (wrapper.getProjectName() != null && projectName.equals(wrapper.getProjectName())) {
                wrapper.getKeystrokeCount().resetData();
                break;
            }
        }
    }

    public KeystrokeCount getKeystrokeCount(String projectName) {
        for (KeystrokeCountWrapper wrapper : keystrokeCountWrapperList) {
            if (wrapper.getProjectName() != null && projectName.equals(wrapper.getProjectName())) {
                return wrapper.getKeystrokeCount();
            }
        }
        return null;
    }

    public void setKeystrokeCount(String projectName, KeystrokeCount keystrokeCount) {
        for (KeystrokeCountWrapper wrapper : keystrokeCountWrapperList) {
            if (wrapper.getProjectName() != null && projectName.equals(wrapper.getProjectName())) {
                wrapper.setKeystrokeCount(keystrokeCount);
                return;
            }
        }

        // didn't find it, time to create a wrapper
        KeystrokeCountWrapper wrapper = new KeystrokeCountWrapper();
        wrapper.setKeystrokeCount(keystrokeCount);
        wrapper.setProjectName(projectName);
        keystrokeCountWrapperList.add(wrapper);
    }

    public KeystrokeCountWrapper getKeystrokeWrapper(String projectName) {
        for (KeystrokeCountWrapper wrapper : keystrokeCountWrapperList) {
            if (wrapper.getProjectName() != null && projectName.equals(wrapper.getProjectName())) {
                return wrapper;
            }
        }
        return null;
    }

    public List<KeystrokeCountWrapper> getKeystrokeCountWrapperList() {
        return this.keystrokeCountWrapperList;
    }

    public class KeystrokeCountWrapper {
        // KeystrokeCount cache metadata
        protected KeystrokeCount keystrokeCount;
        protected String projectName = "";
        protected String currentFileName = "";
        protected int currentTextLength = 0;

        public KeystrokeCount getKeystrokeCount() {
            return keystrokeCount;
        }

        public void setKeystrokeCount(KeystrokeCount keystrokeCount) {
            this.keystrokeCount = keystrokeCount;
        }

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public String getCurrentFileName() {
            return currentFileName;
        }

        public void setCurrentFileName(String currentFileName) {
            this.currentFileName = currentFileName;
        }

        public int getCurrentTextLength() {
            return currentTextLength;
        }

        public void setCurrentTextLength(int currentTextLength) {
            this.currentTextLength = currentTextLength;
        }
    }

}
