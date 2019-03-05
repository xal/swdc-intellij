/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved...
 */
package com.softwareco.intellij.plugin;

import com.google.gson.JsonObject;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.extensions.PluginId;

public class KeystrokeCount {

    // TODO: backend driven, we should look at getting a list of types at some point
    private String type = "Events";

    // non-hardcoded attributes
    private JsonObject source = new JsonObject();
    private String version;
    private int pluginId;
    private String keystrokes = "0"; // keystroke count
    // start and end are in seconds
    private long start;
    private long local_start;
    private String os;
    private String timezone;
    private KeystrokeProject project;

    public KeystrokeCount() {
        this.start = Math.round(System.currentTimeMillis() / 1000);
        String appVersion = PluginManager.getPlugin(PluginId.getId("com.softwareco.intellij.plugin")).getVersion();
        if (appVersion != null) {
            this.version = appVersion;
        } else {
            this.version = SoftwareCoUtils.version;
        }
        this.pluginId = SoftwareCoUtils.pluginId;
        this.os = SoftwareCo.getOsInfo();
    }

    public KeystrokeCount clone() {
        KeystrokeCount kc = new KeystrokeCount();
        kc.keystrokes = this.keystrokes;
        kc.start = this.start;
        kc.local_start = this.local_start;
        kc.version = this.version;
        kc.pluginId = this.pluginId;
        kc.project = this.project;
        kc.type = this.type;
        kc.source = this.source;
        kc.timezone = this.timezone;

        return kc;
    }

    public void resetData() {
        this.keystrokes = "0";
        this.source = new JsonObject();
        if (this.project != null) {
            this.project.resetData();
        }
        this.start = 0L;
        this.local_start = 0L;
        this.timezone = "";
    }

    public JsonObject getSourceByFileName(String fileName) {
        if (source.has(fileName)) {
            return source.get(fileName).getAsJsonObject();
        }

        // create one and return the one just created
        JsonObject fileInfoData = new JsonObject();
        fileInfoData.addProperty("add", 0);
        fileInfoData.addProperty("paste", 0);
        fileInfoData.addProperty("open", 0);
        fileInfoData.addProperty("close", 0);
        fileInfoData.addProperty("delete", 0);
        fileInfoData.addProperty("length", 0);
        fileInfoData.addProperty("netkeys", 0);
        // -1 to help identify when setting it the 1st time
        fileInfoData.addProperty("lines", -1);
        fileInfoData.addProperty("linesAdded", 0);
        fileInfoData.addProperty("linesRemoved", 0);
        fileInfoData.addProperty("syntax", 0);
        source.add(fileName, fileInfoData);

        return fileInfoData;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getPluginId() {
        return pluginId;
    }

    public void setPluginId(int pluginId) {
        this.pluginId = pluginId;
    }

    public String getSource() {
        return SoftwareCo.gson.toJson(source);
    }

    public boolean hasData() {
        if (Integer.parseInt(this.getKeystrokes()) > 0) {
            return true;
        }

        return false;
    }

    private boolean hasValueDataForProperty(JsonObject fileInfoData, String property) {
        try {
            int val = fileInfoData.getAsJsonPrimitive(property).getAsInt();
            if (val > 0) {
                return true;
            }
        } catch (Exception e) {}
        return false;
    }

    public String getKeystrokes() {
        return keystrokes;
    }

    public void setKeystrokes(String keystrokes) {
        this.keystrokes = keystrokes;
    }

    public long getStart() {
        return start;
    }

    public void setStart(long start) {
        this.start = start;
    }

    public long getLocal_start() {
        return local_start;
    }

    public void setLocal_start(long local_start) {
        this.local_start = local_start;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public KeystrokeProject getProject() {
        return project;
    }

    public void setProject(KeystrokeProject project) {
        this.project = project;
    }

    @Override
    public String toString() {
        return "KeystrokeCount{" +
                "type='" + type + '\'' +
                ", pluginId=" + pluginId +
                ", source=" + source +
                ", keystrokes='" + keystrokes + '\'' +
                ", start=" + start +
                ", local_start=" + local_start +
                ", timezone='" + timezone + '\'' +
                ", project=" + project +
                '}';
    }
}
