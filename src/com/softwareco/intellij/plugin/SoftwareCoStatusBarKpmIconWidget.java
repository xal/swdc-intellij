/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.MouseEvent;

public class SoftwareCoStatusBarKpmIconWidget implements StatusBarWidget {
    public static final Logger log = Logger.getInstance("SoftwareCoStatusBarKpmIconWidget");

    public static final String KPM_ICON_ID = "software.kpm.icon";
    public static final String SESSION_TIME_ICON_ID = "software.session.time.icon";

    private Icon icon = null;
    private String tooltip = "";
    private String id = KPM_ICON_ID;

    private final IconPresentation presentation = new IconPresentation();
    private Consumer<MouseEvent> eventHandler;

    public SoftwareCoStatusBarKpmIconWidget(String id) {
        this.id = id;
        eventHandler = new Consumer<MouseEvent>() {
            @Override
            public void consume(MouseEvent mouseEvent) {
                SoftwareCoSessionManager.launchDashboard();
            }
        };
    }

    public void setIcon(Icon icon) {
        this.icon = icon;
    }

    public void setTooltip(String tooltip) {
        this.tooltip = tooltip;
    }

    class IconPresentation implements StatusBarWidget.IconPresentation {

        @NotNull
        @Override
        public Icon getIcon() {
            return SoftwareCoStatusBarKpmIconWidget.this.icon;
        }

        @Nullable
        @Override
        public String getTooltipText() {
            return SoftwareCoStatusBarKpmIconWidget.this.tooltip;
        }

        @Nullable
        @Override
        public Consumer<MouseEvent> getClickConsumer() {
            return eventHandler;
        }
    }

    @Override
    public WidgetPresentation getPresentation(@NotNull PlatformType type) {
        return presentation;
    }

    @NotNull
    @Override
    public String ID() {
        return id;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
    }

    @Override
    public void dispose() {
    }
}
