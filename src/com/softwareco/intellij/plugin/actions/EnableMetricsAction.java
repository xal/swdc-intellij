/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.softwareco.intellij.plugin.SoftwareCo;
import com.softwareco.intellij.plugin.SoftwareCoUtils;

public class EnableMetricsAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {
        SoftwareCo.TELEMTRY_ON = true;
        SoftwareCoUtils.setStatusLineMessage("Code Time", "Click to see more from Code Time");
    }

    @Override
    public void update(AnActionEvent event) {
        event.getPresentation().setVisible(true);
        event.getPresentation().setEnabled(!SoftwareCo.TELEMTRY_ON);
    }
}
