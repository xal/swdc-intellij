/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.softwareco.intellij.plugin.SoftwareCo;
import com.softwareco.intellij.plugin.SoftwareCoUtils;

public class EnableMetricsAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {
        SoftwareCo.TELEMTRY_ON = true;
        SoftwareCoUtils.setStatusLineMessage("Software.com", "",
                "", "", "Click to see more from Software.com");
    }

    @Override
    public void update(AnActionEvent event) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        event.getPresentation().setVisible(true);
        event.getPresentation().setEnabled(!SoftwareCo.TELEMTRY_ON);
    }
}
