/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.extensions.PluginId;

import java.util.Map;
import java.util.Set;

public class KeystrokeCount {

    // TODO: backend driven, we should look at getting a list of types at some point
    private String type = "Events";
    // sublime = 1, vs code = 2, eclipse = 3, intellij = 4, visual studio = 6, atom = 7
    private int pluginId = 4;
    private String version = "0.1.9";

    // non-hardcoded attributes
    private JsonObject source = new JsonObject();
    private String data = "0"; // keystroke count
    // start and end are in seconds
    private long start;
    private long end;
    private KeystrokeProject project;

    public KeystrokeCount() {
        this.start = Math.round(System.currentTimeMillis() / 1000);
        String appVersion = PluginManager.getPlugin(PluginId.getId("com.softwareco.intellij.plugin")).getVersion();
        if (appVersion != null) {
            this.version = appVersion;
        }
    }

    public KeystrokeCount clone() {
        KeystrokeCount kc = new KeystrokeCount();
        kc.data = this.data;
        kc.start = this.start;
        kc.end = this.end;
        kc.version = this.version;
        kc.pluginId = this.pluginId;
        kc.project = this.project;
        kc.type = this.type;
        kc.source = this.source;

        return kc;
    }

    public void resetData() {
        this.data = "0";
        this.source = new JsonObject();
        if (this.project != null) {
            this.project.resetData();
        }
        this.start = 0L;
        this.end = 0L;
    }

    public JsonObject getSourceByFileName(String fileName) {
        if (source.has(fileName)) {
            return source.get(fileName).getAsJsonObject();
        }

        // create one and return the one just created
        JsonObject fileInfoData = new JsonObject();
        fileInfoData.addProperty("keys", 0);
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
        Set<Map.Entry<String, JsonElement>> fileInfoDataSet = this.source.entrySet();
        for ( Map.Entry<String, JsonElement> fileInfoData : fileInfoDataSet ) {
            JsonObject fileinfoDataJsonObj = (JsonObject) fileInfoData.getValue();
            // go through all of the different types of event vals and check if we have an incremented value
            if (this.hasValueDataForProperty(fileinfoDataJsonObj, "add") ||
                    this.hasValueDataForProperty(fileinfoDataJsonObj, "open") ||
                    this.hasValueDataForProperty(fileinfoDataJsonObj, "close") ||
                    this.hasValueDataForProperty(fileinfoDataJsonObj, "paste") ||
                    this.hasValueDataForProperty(fileinfoDataJsonObj, "delete")) {
                return true;
            }
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

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public long getStart() {
        return start;
    }

    public void setStart(long start) {
        this.start = start;
    }

    public long getEnd() {
        return end;
    }

    public void setEnd(long end) {
        this.end = end;
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
                ", data='" + data + '\'' +
                ", start=" + start +
                ", end=" + end +
                ", project=" + project +
                '}';
    }
}
