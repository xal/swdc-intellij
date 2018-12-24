package com.softwareco.intellij.plugin;

import com.google.common.collect.Maps;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.Map;

public class SoftwareCoRepoManager {

    public static final Logger log = Logger.getInstance("SoftwareCoRepoManager");

    private static SoftwareCoRepoManager instance = null;

    private JsonObject currentTrack = new JsonObject();

    public static SoftwareCoRepoManager getInstance() {
        if (instance == null) {
            instance = new SoftwareCoRepoManager();
        }
        return instance;
    }

    public void processRepoMembersInfo(final String projectDir) {
        JsonObject resource = SoftwareCoUtils.getResourceInfo(projectDir);
        if (resource.has("identifier")) {
            String identifier = resource.get("identifier").getAsString();
            String tag = (resource.has("tag")) ? resource.get("tag").getAsString() : "";
            String branch = (resource.has("branch")) ? resource.get("branch").getAsString(): "";

            String[] identifierCmd = { "git", "log", "--pretty='%an,%ae'" };
            String devOutput = SoftwareCoUtils.runCommand(identifierCmd, projectDir);

            // String[] devList = devOutput.replace(/\r\n/g, "\r").replace(/\n/g, "\r").split(/\r/);
            String[] devList = devOutput.split("\n");
            JsonArray members = new JsonArray();
            Map<String, String> memberMap = Maps.newHashMap();
            if (devList != null && devList.length > 0) {
                for (String line : devList) {
                    String[] parts = line.split(",");
                    if (parts != null && parts.length > 1) {
                        String name = parts[0].trim();
                        String email = parts[1].trim();
                        if (!memberMap.containsKey(email)) {
                            memberMap.put(email, name);
                            JsonObject json = new JsonObject();
                            json.addProperty("email", email);
                            json.addProperty("name", name);
                            members.add(json);
                        }
                    }
                }
            }

            if (members.size() > 0) {
                // send the members
                try {
                    JsonObject repoData = new JsonObject();
                    repoData.add("members", members);
                    repoData.addProperty("identifier", identifier);
                    repoData.addProperty("tag", tag);
                    repoData.addProperty("branch", branch);
                    String repoDataStr = repoData.toString();
                    JsonObject responseData = SoftwareCoUtils.getResponseInfo(
                            SoftwareCoSessionManager.makeApiCall(
                                    "/repo/members", true, repoDataStr)).jsonObj;

                    // {"status":"success","message":"Updated repo members"}
                    // {"status":"failed","data":"Unable to process repo information"}
                    if (responseData != null && responseData.has("message")) {
                        log.debug("Software.com: " + responseData.get("message").getAsString());
                    } else if (responseData != null && responseData.has("data")) {
                        log.debug("Software.com: " + responseData.get("data").getAsString());
                    } else {
                        log.debug("Software.com: Unable to process repo member metrics");
                    }
                } catch (Exception e) {
                    log.debug("Software.com: Unable to process repo member metrics, error: " + e.getMessage());
                }
            }
        }
    }
}
