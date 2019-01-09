/**
 * Copyright (c) 2018 by Software.com
 * All rights reserved
 */
package com.softwareco.intellij.plugin;

import java.io.IOException;
import java.net.SocketException;
import java.time.ZonedDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.softwareco.intellij.plugin.SoftwareCoUtils;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;

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

    protected class MusicSendDataTask implements Callable<HttpResponse> {

        @Override
        public HttpResponse call() throws Exception {
            //
            // get the music track json string
            //
            JsonObject trackInfo = SoftwareCoUtils.getCurrentMusicTrack();

            if (trackInfo == null || !trackInfo.has("id") || !trackInfo.has("name")) {
                return null;
            }

            HttpResponse response = null;

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

            if (trackId != null) {

                if (existingTrackId != null && !existingTrackId.equals(trackId)) {
                    // update the end time on the previous track and send it as well
                    currentTrack.addProperty("end", now);
                    // send the post to end the previous track
                    postTrackInfo(SoftwareCo.gson.toJson(currentTrack));
                }


                // if the current track doesn't have an "id" then a song has started
                if (existingTrackId == null  || !existingTrackId.equals(trackId)) {

                    // send the post to send the new track info
                    trackInfo.addProperty("start", now);
                    trackInfo.addProperty("local_start", local_start);

                    response = postTrackInfo(SoftwareCo.gson.toJson(trackInfo));

                    // update the current track
                    cloneTrackInfoToCurrent(trackInfo);
                }

            } else {
                if (existingTrackId != null) {
                    // update the end time on the previous track and send it as well
                    currentTrack.addProperty("end", now);
                    // send the post to end the previous track
                    response = postTrackInfo(SoftwareCo.gson.toJson(currentTrack));
                }

                // song has ended, clear out the current track
                currentTrack = new JsonObject();
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

        private HttpResponse postTrackInfo(String trackInfo) {
            HttpPost request = null;
            try {

                //
                // Add the json body to the outgoing post request
                //
                request = new HttpPost(SoftwareCoUtils.api_endpoint + "/data/music");
                String jwtToken = SoftwareCoSessionManager.getItem("jwt");
                // we need the header, but check if it's null anyway
                if (jwtToken != null) {
                    request.addHeader("Authorization", jwtToken);
                }
                StringEntity params = new StringEntity(trackInfo);
                request.addHeader("Content-type", "application/json");
                request.setEntity(params);

                //
                // Send the POST request
                //
                SoftwareCoUtils.logApiRequest(request, trackInfo);
                HttpResponse response = SoftwareCoUtils.httpClient.execute(request);
                //
                // Return the response
                //
                return response;
            } catch (Exception e) {
                log.error("Software.com: Unable to send the keystroke payload request.", e);
            } finally {
                if (request != null) {
                    request.releaseConnection();
                }
            }
            return null;
        }
    }


    public void processMusicTrackInfo() {

        ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {

                MusicSendDataTask sendTask = new MusicSendDataTask();

                Future<HttpResponse> response = SoftwareCoUtils.executorService.submit(sendTask);

                //
                // Handle the Future if it exist
                //
                if ( response != null ) {
                    HttpResponse httpResponse = null;
                    try {
                        httpResponse = response.get();

                        if (httpResponse != null) {
                            //
                            // Handle the response from the Future (consume the entity to prevent connection pool leak/timeout)
                            //
                            String entityResult = "";
                            if (httpResponse.getEntity() != null) {
                                try {
                                    entityResult = EntityUtils.toString(httpResponse.getEntity());
                                } catch (SocketException e) {
                                    // no need to post an error, we're just unable to communicate right now
                                } catch (Exception e) {
                                    log.error("Software.com: error retrieving response.", e.getMessage());
                                }
                            }

                            //
                            // If it's a response status of anything other than the 200 series then the POST request failed
                            //
                            int responseStatus = httpResponse.getStatusLine().getStatusCode();
                            if (responseStatus >= 300) {
                                log.error("Software.com: Unable to send the music track payload, "
                                        + "response: [status: " + responseStatus + ", entityResult: '" + entityResult + "']");
                            }
                        }

                    } catch (InterruptedException | ExecutionException e) {
                        log.error("Software.com: Unable to get the music track response from the http request.", e);
                    }
                }

            }
        });
    }
}
