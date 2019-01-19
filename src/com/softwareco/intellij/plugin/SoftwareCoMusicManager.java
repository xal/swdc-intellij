/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import java.time.ZonedDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import org.apache.http.client.methods.HttpPost;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class SoftwareCoMusicManager {

    public static final Logger log = Logger.getInstance("SoftwareCoMusicManager");

    private static SoftwareCoMusicManager instance = null;

    private JsonObject currentTrack = new JsonObject();

    public static SoftwareCoMusicManager getInstance() {
        if (instance == null) {
            instance = new SoftwareCoMusicManager();
        }
        return instance;
    }

    protected class MusicSendDataTask implements Callable<SoftwareResponse> {

        @Override
        public SoftwareResponse call() throws Exception {
            //
            // get the music track json string
            //
            JsonObject trackInfo = SoftwareCoUtils.getCurrentMusicTrack();

            if (trackInfo == null || !trackInfo.has("id") || !trackInfo.has("name")) {
                return null;
            }

            SoftwareResponse response = null;

            String existingTrackId = (currentTrack.has("id")) ? currentTrack.get("id").getAsString() : null;
            String trackId = (trackInfo != null && trackInfo.has("id")) ? trackInfo.get("id").getAsString() : null;

            if (trackId != null && trackId.indexOf("spotify") == -1 && trackId.indexOf("itunes") == -1) {
                // update it to itunes since spotify uses that in the id
                trackId = "itunes:track:" + trackId;
                trackInfo.addProperty("id", trackId);
            }

            Integer offset  = ZonedDateTime.now().getOffset().getTotalSeconds();
            long now = Math.round(System.currentTimeMillis() / 1000);
            long local_start = now + offset;

            String trackStr = null;

            if (trackId != null) {

                if (existingTrackId != null && !existingTrackId.equals(trackId)) {
                    // update the end time on the previous track and send it as well
                    currentTrack.addProperty("end", now);
                    // send the post to end the previous track
                    trackStr = SoftwareCo.gson.toJson(currentTrack);
                }


                // if the current track doesn't have an "id" then a song has started
                if (existingTrackId == null  || !existingTrackId.equals(trackId)) {

                    // send the post to send the new track info
                    trackInfo.addProperty("start", now);
                    trackInfo.addProperty("local_start", local_start);

                    trackStr = SoftwareCo.gson.toJson(trackInfo);

                    // update the current track
                    cloneTrackInfoToCurrent(trackInfo);
                }

            } else {
                if (existingTrackId != null) {
                    // update the end time on the previous track and send it as well
                    currentTrack.addProperty("end", now);
                    // send the post to end the previous track
                    trackStr = SoftwareCo.gson.toJson(currentTrack);
                }

                // song has ended, clear out the current track
                currentTrack = new JsonObject();
            }

            if (trackStr != null) {
                response = SoftwareCoUtils.makeApiCall("/data/music", HttpPost.METHOD_NAME, trackStr);
            }

            return response;
        }

        private void cloneTrackInfoToCurrent(JsonObject trackInfo) {
            currentTrack = new JsonObject();
            currentTrack.addProperty("start", trackInfo.get("start").getAsLong());
            long end = (trackInfo.has("end")) ? trackInfo.get("end").getAsLong() : 0;
            currentTrack.addProperty("end", end);
            currentTrack.addProperty("local_start", trackInfo.get("local_start").getAsLong());
            JsonElement durationElement = (trackInfo.has("duration")) ? trackInfo.get("duration") : null;
            double duration = 0;
            if (durationElement != null) {
                String durationStr = durationElement.getAsString();
                duration = Double.parseDouble(durationStr);
                if (duration > 1000) {
                    duration /= 1000;
                }
            }
            currentTrack.addProperty("duration", duration);
            String genre = (trackInfo.has("genre")) ? trackInfo.get("genre").getAsString() : "";
            currentTrack.addProperty("genre", genre);
            String artist = (trackInfo.has("artist")) ? trackInfo.get("artist").getAsString() : "";
            currentTrack.addProperty("artist", artist);
            currentTrack.addProperty("name", trackInfo.get("name").getAsString());
            String state = (trackInfo.has("state")) ? trackInfo.get("state").getAsString() : "";
            currentTrack.addProperty("state", state);
            currentTrack.addProperty("id", trackInfo.get("id").getAsString());
        }
    }


    public void processMusicTrackInfo() {

        ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {

                MusicSendDataTask sendTask = new MusicSendDataTask();

                Future<SoftwareResponse> response = SoftwareCoUtils.executorService.submit(sendTask);

                //
                // Handle the Future if it exist
                //
                if ( response != null ) {
                    SoftwareResponse httpResponse = null;
                    try {
                        httpResponse = response.get();

                        if (httpResponse == null || !httpResponse.isOk()) {
                            String errorStr = (httpResponse != null && httpResponse.getErrorMessage() != null) ? httpResponse.getErrorMessage() : "";
                            log.info("Software.com: Unable to get the music track response from the http request, error: " + errorStr);
                        }

                    } catch (InterruptedException | ExecutionException e) {
                        log.info("Software.com: Unable to get the music track response from the http request, error: " + e.getMessage());
                    }
                }

            }
        });
    }
}
