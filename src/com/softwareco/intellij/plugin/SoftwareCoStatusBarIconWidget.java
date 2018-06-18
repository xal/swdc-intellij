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

public class SoftwareCoStatusBarIconWidget implements StatusBarWidget {
    public static final Logger log = Logger.getInstance("SoftwareCoStatusBarIconWidget");

    public static final String WIDGET_ID = "software.icon.com";

    private Icon icon = null;
    private String tooltip = "";

    private final IconPresentation presentation = new IconPresentation();
    private Consumer<MouseEvent> eventHandler;

    public SoftwareCoStatusBarIconWidget() {
        eventHandler = new Consumer<MouseEvent>() {
            @Override
            public void consume(MouseEvent mouseEvent) {
                String url = SoftwareCoUtils.launch_url;
                String jwtToken = SoftwareCoSessionManager.getItem("jwt");
                if (jwtToken == null) {
                    String token = SoftwareCoSessionManager.generateToken();
                    SoftwareCoSessionManager.setItem("token", token);
                    url += "/login?token=" + token;
                }
                BrowserUtil.browse(url);
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
            return SoftwareCoStatusBarIconWidget.this.icon;
        }

        @Nullable
        @Override
        public String getTooltipText() {
            return SoftwareCoStatusBarIconWidget.this.tooltip;
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
        return WIDGET_ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
    }

    @Override
    public void dispose() {
    }
}
