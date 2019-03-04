/**
 * Copyright (c) 2019 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.softwareco.intellij.plugin.SoftwareCoUtils;

public class SoftwareLogoutAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {
        SoftwareCoUtils.pluginLogout();
    }

    @Override
    public void update(AnActionEvent event) {
        SoftwareCoUtils.UserStatus userStatus = SoftwareCoUtils.getUserStatus();
        String userEmailTxt = (userStatus.email != null) ? " (" + userStatus.email + ")" : "";
        event.getPresentation().setVisible(userStatus.loggedInUser != null);
        event.getPresentation().setEnabled(true);
        event.getPresentation().setText("Log out from Code Time" + userEmailTxt);
    }
}
