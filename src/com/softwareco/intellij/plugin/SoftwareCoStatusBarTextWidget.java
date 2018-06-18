package com.softwareco.intellij.plugin;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;

public class SoftwareCoStatusBarTextWidget implements StatusBarWidget {
    public static final Logger log = Logger.getInstance("SoftwareCoStatusBarWidget");

    public static final String WIDGET_ID = "software.com";

    private String msg = "";
    private String tooltip = "";

    private Consumer<MouseEvent> eventHandler;

    private final TextPresentation presentation = new StatusPresentation();

    public SoftwareCoStatusBarTextWidget() {
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

    public void setText(String msg) {
        this.msg = msg;
    }

    public void setTooltip(String tooltip) {
        this.tooltip = tooltip;
    }

    class StatusPresentation implements StatusBarWidget.TextPresentation {
        @Override
        public float getAlignment() {
            return 0;
        }

        @NotNull
        @Override
        public String getText() {
            return SoftwareCoStatusBarTextWidget.this.msg;
        }

        @NotNull
        @Override
        public String getMaxPossibleText() {
            return "";
        }

        @Override
        public String getTooltipText() {
            return SoftwareCoStatusBarTextWidget.this.tooltip;
        }

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
