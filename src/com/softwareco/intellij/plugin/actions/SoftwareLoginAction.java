/**
 * Copyright (c) 2019 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.softwareco.intellij.plugin.SoftwareCoSessionManager;
import com.softwareco.intellij.plugin.SoftwareCoUtils;

public class SoftwareLoginAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {
        SoftwareCoSessionManager.launchLogin();
    }

    @Override
    public void update(AnActionEvent event) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        SoftwareCoUtils.UserStatus userStatus = SoftwareCoUtils.getUserStatus();
        event.getPresentation().setVisible(userStatus.loggedInUser == null);
        event.getPresentation().setEnabled(true);
    }
}
