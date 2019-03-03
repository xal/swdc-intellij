/**
 * Copyright (c) 2019 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.softwareco.intellij.plugin.SoftwareCoUtils;

public class CodeTimeMetricsAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {
        SoftwareCoUtils.launchCodeTimeMetricsDashboard();
    }

    @Override
    public void update(AnActionEvent event) {
        SoftwareCoUtils.UserStatus userStatus = SoftwareCoUtils.getUserStatus();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        event.getPresentation().setVisible(userStatus.hasAccounts);
        event.getPresentation().setEnabled(true);
    }
}
