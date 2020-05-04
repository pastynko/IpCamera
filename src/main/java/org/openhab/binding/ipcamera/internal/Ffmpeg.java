/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.binding.ipcamera.internal;

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Ffmpeg} class is responsible for handling multiple ffmpeg conversions which are used for many tasks
 *
 *
 * @author Matthew Skinner - Initial contribution
 */

@NonNullByDefault
public class Ffmpeg {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private IpCameraHandler ipCameraHandler;
    private @Nullable Process process = null;
    private String ffmpegCommand = "", format = "";
    private String[] commandArray;
    private StreamRunning streamRunning = new StreamRunning();
    private int keepAlive = 60;
    boolean running = false;

    public void setKeepAlive(int seconds) {
        if (seconds == -1) {
            keepAlive = -1;
        } else if (keepAlive != -1) {
            keepAlive = seconds
                    / (Integer.parseInt(ipCameraHandler.config.get(CONFIG_POLL_CAMERA_MS).toString()) / 1000);
        }
    }

    public void checkKeepAlive() {
        if (keepAlive <= -1) {
            return;
        } else if (keepAlive == 0) {
            stopConverting();
        } else {
            keepAlive--;
        }
        return;
    }

    public Ffmpeg(IpCameraHandler handle, String format, String ffmpegLocation, String inputArguments, String input,
            String outArguments, String output, String username, String password) {
        this.format = format;
        ipCameraHandler = handle;
        String altInput = input;
        // Input can be snapshots not just rtsp or http
        if (!password.equals("") && !input.contains("@") && input.contains("rtsp")) {
            String credentials = username + ":" + password + "@";
            // will not work for https: but currently binding does not use https
            altInput = input.substring(0, 7) + credentials + input.substring(7);
        }
        ffmpegCommand = ffmpegLocation + " " + inputArguments + " -i " + altInput + " " + outArguments + " " + output;
        commandArray = ffmpegCommand.trim().split("\\s+");
    }

    @NonNullByDefault
    private class StreamRunning extends Thread {
        public int countOfMotions = 0;

        @Override
        public void run() {
            try {
                process = Runtime.getRuntime().exec(commandArray);
                if (process != null) {
                    InputStream errorStream = process.getErrorStream();
                    InputStreamReader errorStreamReader = new InputStreamReader(errorStream);
                    BufferedReader bufferedReader = new BufferedReader(errorStreamReader);
                    String line = null;
                    while ((line = bufferedReader.readLine()) != null) {
                        if (format.equals("RTSPHELPER")) {
                            logger.debug("{}", line);
                            if (line.contains("lavfi.")) {
                                if (countOfMotions == 3) {
                                    ipCameraHandler.motionDetected(CHANNEL_MOTION_ALARM);
                                } else {
                                    countOfMotions++;
                                }
                            } else if (line.contains("speed=")) {
                                if (countOfMotions > 0) {
                                    countOfMotions--;
                                    if (countOfMotions == 0) {
                                        ipCameraHandler.noMotionDetected(CHANNEL_MOTION_ALARM);
                                    }
                                }
                            } else if (line.contains("silence_start")) {
                                ipCameraHandler.setChannelState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("OFF"));
                                ipCameraHandler.firstAudioAlarm = false;
                                ipCameraHandler.audioAlarmUpdateSnapshot = false;
                            } else if (line.contains("silence_end")) {
                                ipCameraHandler.audioDetected();
                            }
                        } else {
                            logger.debug("{}", line);
                        }
                    }
                }
            } catch (IOException e) {
                logger.error("{}", e.toString());
            } finally {
                switch (format) {
                    case "GIF":
                        logger.debug("Animated GIF has been created and is ready for use.");
                        try {
                            // Without a small delay, Pushover sends no file 10% of time.
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                        }
                        ipCameraHandler.setChannelState(CHANNEL_UPDATE_GIF, OnOffType.valueOf("OFF"));
                        break;
                    case "RECORD":
                        logger.debug("MP4 has been created and is ready for use.");
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                        }
                        ipCameraHandler.setChannelState(CHANNEL_RECORD_MP4, DecimalType.ZERO);
                        break;
                }
            }
        }
    }

    public void startConverting() {
        if (!streamRunning.isAlive()) {
            streamRunning = new StreamRunning();
            logger.debug("Starting ffmpeg with this command now:{}", ffmpegCommand);
            streamRunning.start();
            running = true;
            if (format.equals("HLS")) {
                ipCameraHandler.setChannelState(CHANNEL_START_STREAM, OnOffType.valueOf("ON"));
                if (keepAlive > -1) {
                    try {
                        Thread.sleep(4500); // Used for on demand HLS to give ffmpeg time to produce the files needed.
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
        if (keepAlive != -1) {
            keepAlive = 60;
        }
    }

    public boolean getIsAlive() {
        return running;
    }

    public void stopConverting() {
        if (streamRunning.isAlive()) {
            logger.debug("Stopping ffmpeg now");
            running = false;
            if (process != null) {
                process.destroyForcibly();
            }
            keepAlive = 60;
            if (format.equals("HLS")) {
                ipCameraHandler.setChannelState(CHANNEL_START_STREAM, OnOffType.valueOf("OFF"));
            }
        }
    }
}
