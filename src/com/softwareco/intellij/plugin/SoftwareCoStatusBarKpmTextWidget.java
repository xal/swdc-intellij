package com.softwareco.intellij.plugin;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;

public class SoftwareCoStatusBarKpmTextWidget implements StatusBarWidget {
    public static final Logger log = Logger.getInstance("SoftwareCoStatusBarKpmTextWidget");

    public static final String KPM_TEXT_ID = "software.kpm.text";

    private String msg = "";
    private String tooltip = "";
    private String id;

    private Consumer<MouseEvent> eventHandler;

    private final TextPresentation presentation = new StatusPresentation();

    public SoftwareCoStatusBarKpmTextWidget(String id) {
        this.id = id;
        eventHandler = new Consumer<MouseEvent>() {
            @Override
            public void consume(MouseEvent mouseEvent) {
                // SoftwareCoSessionManager.launchDashboard();
                SoftwareCoUtils.launchCodeTimeMetricsDashboard();
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

        @NotNull
        @Override
        public String getText() {
            return SoftwareCoStatusBarKpmTextWidget.this.msg;
        }

        @NotNull
        @Override
        public String getMaxPossibleText() {
            return "";
        }

        @Override
        public float getAlignment() {
            return 0;
        }

        @Nullable
        @Override
        public String getTooltipText() {
            return SoftwareCoStatusBarKpmTextWidget.this.tooltip;
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
