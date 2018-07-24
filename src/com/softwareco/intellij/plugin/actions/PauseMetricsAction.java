/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.softwareco.intellij.plugin.SoftwareCo;

public class PauseMetricsAction extends AnAction {
    public static final Logger log = Logger.getInstance("PauseMetricsAction");

    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {
        SoftwareCo.TELEMTRY_ON = false;
    }

    @Override
    public void update(AnActionEvent event) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        event.getPresentation().setVisible(true);
        event.getPresentation().setEnabled(SoftwareCo.TELEMTRY_ON);
    }
}
