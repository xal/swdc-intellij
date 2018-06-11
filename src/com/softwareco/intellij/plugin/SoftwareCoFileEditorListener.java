package com.softwareco.intellij.plugin;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;

public class SoftwareCoFileEditorListener implements FileEditorManagerListener {

    @Override
    public void fileOpened(FileEditorManager manager, VirtualFile file) {
        if (file == null || file.getPath() == null) {
            return;
        }

        SoftwareCo.handleFileOpenedEvents(file.getPath(), manager.getProject());
    }

    @Override
    public void fileClosed(FileEditorManager manager, VirtualFile file) {
        if (file == null || file.getPath() == null) {
            return;
        }

        SoftwareCo.handleFileClosedEvents(file.getPath(), manager.getProject());
    }
}
