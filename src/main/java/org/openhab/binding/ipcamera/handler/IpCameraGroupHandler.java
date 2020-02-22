/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
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

package org.openhab.binding.ipcamera.handler;

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.ipcamera.internal.StreamServerGroupHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * The {@link IpCameraGroupHandler} is responsible for finding cameras that are part of this group and displaying a
 * group picture.
 *
 * @author Matthew Skinner - Initial contribution
 */

public class IpCameraGroupHandler extends BaseThingHandler {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = new HashSet<ThingTypeUID>(
            Arrays.asList(THING_TYPE_GROUPDISPLAY));
    private Configuration config;
    BigDecimal pollTimeInSeconds = new BigDecimal(2);
    public ArrayList<IpCameraHandler> cameraOrder = new ArrayList<IpCameraHandler>(2);
    private EventLoopGroup serversLoopGroup = new NioEventLoopGroup();
    private final ScheduledExecutorService pollCameraGroup = Executors.newSingleThreadScheduledExecutor();
    private @Nullable ScheduledFuture<?> pollCameraGroupJob = null;
    private @Nullable ServerBootstrap serverBootstrap;
    private @Nullable ChannelFuture serverFuture = null;
    public String hostIp = "0.0.0.0";
    boolean motionChangesOrder = true;
    public int serverPort = 0;
    public String playList = "";
    String playingNow = "";
    public int cameraIndex = 0;
    public boolean hlsTurnedOn = false;
    int entries = 0;
    BigDecimal numberOfFiles = new BigDecimal(1);
    int mediaSequence = 1;

    public IpCameraGroupHandler(Thing thing) {
        super(thing);
    }

    public String getWhiteList() {
        return config.get(CONFIG_IP_WHITELIST).toString();
    }

    public String getPlayList() {
        return playList;
    }

    public String getOutputFolder(int index) {
        IpCameraHandler handle = cameraOrder.get(index);
        return (String) handle.config.get(CONFIG_FFMPEG_OUTPUT);
    }

    private String readCamerasPlaylist(int cameraIndex) {
        String camerasm3u8 = "";
        IpCameraHandler handle = cameraOrder.get(cameraIndex);
        try {
            String file = handle.config.get(CONFIG_FFMPEG_OUTPUT).toString() + "ipcamera.m3u8";
            camerasm3u8 = new String(Files.readAllBytes(Paths.get(file)));
        } catch (IOException e) {
            logger.error("error occured:{}", e);
        }
        return camerasm3u8;
    }

    String keepLast(String string, int numberToRetain) {
        int start = string.length();
        for (int loop = numberToRetain; loop > 0; loop--) {
            start = string.lastIndexOf("#EXTINF:", start - 1);
        }
        entries = entries + numberToRetain;
        return string.substring(start);
    }

    String removeFromStart(String string, int numberToRemove) {
        int start = 0;
        for (int loop = numberToRemove; loop > 0; loop--) {
            start = string.indexOf("#EXTINF:", start + 1);
        }
        mediaSequence = mediaSequence + numberToRemove;
        entries = entries - numberToRemove;
        return playingNow.substring(start);
    }

    public void setPlayList() {
        String m3u8File = readCamerasPlaylist(cameraIndex);
        BigDecimal segmentLength = new BigDecimal(
                m3u8File.substring(m3u8File.lastIndexOf("#EXTINF:") + 8, m3u8File.lastIndexOf(",")));
        logger.debug("Segments are {} seconds long", segmentLength);
        numberOfFiles = pollTimeInSeconds.divide(segmentLength, 6, RoundingMode.UP);
        logger.debug("Keeping the last {} files from the cameras playlist.", numberOfFiles.intValue());
        if (numberOfFiles.intValue() < 1) {
            numberOfFiles = new BigDecimal(1);
        }
        logger.debug("Passing files to keep now");
        m3u8File = keepLast(m3u8File, numberOfFiles.intValue());
        logger.debug("replacing files to keep now");
        m3u8File = m3u8File.replace("ipcamera", cameraIndex + "ipcamera"); // add index so we can then fetch output path
        logger.debug("calculating segments to keep now");
        if ((segmentLength.intValue() * entries) > pollTimeInSeconds.intValue() * 3) {
            playingNow = removeFromStart(playingNow, numberOfFiles.intValue());
        }
        // logger.debug("Done, updating playlist.");
        playingNow = playingNow + "#EXT-X-DISCONTINUITY\n" + m3u8File;
        playList = "#EXTM3U\n" + "#EXT-X-VERSION:3\n" + // "#EXT-X-ALLOW-CACHE:NO\n" +
                "#EXT-X-TARGETDURATION:5\n" + "#EXT-X-MEDIA-SEQUENCE:" + mediaSequence + "\n" + playingNow;
    }

    private IpCameraGroupHandler getHandle() {
        return this;
    }

    public String getLocalIpAddress() {
        String ipAddress = "";
        try {
            for (Enumeration<NetworkInterface> enumNetworks = NetworkInterface.getNetworkInterfaces(); enumNetworks
                    .hasMoreElements();) {
                NetworkInterface networkInterface = enumNetworks.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = networkInterface.getInetAddresses(); enumIpAddr
                        .hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress.getHostAddress().toString().length() < 18
                            && inetAddress.isSiteLocalAddress()) {
                        ipAddress = inetAddress.getHostAddress().toString();
                        logger.debug("Possible NIC/IP match found:{}", ipAddress);
                    }
                }
            }
        } catch (SocketException ex) {
        }
        return ipAddress;
    }

    public void startStreamServer(boolean start) {

        if (!start) {
            serversLoopGroup.shutdownGracefully(8, 8, TimeUnit.SECONDS);
            serverBootstrap = null;
        } else {
            if (serverBootstrap == null) {
                hostIp = getLocalIpAddress();
                try {
                    serversLoopGroup = new NioEventLoopGroup();
                    serverBootstrap = new ServerBootstrap();
                    serverBootstrap.group(serversLoopGroup);
                    serverBootstrap.channel(NioServerSocketChannel.class);
                    // IP "0.0.0.0" will bind the server to all network connections//
                    serverBootstrap.localAddress(new InetSocketAddress("0.0.0.0", serverPort));
                    serverBootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            socketChannel.pipeline().addLast("idleStateHandler", new IdleStateHandler(0, 25, 0));
                            socketChannel.pipeline().addLast("HttpServerCodec", new HttpServerCodec());
                            socketChannel.pipeline().addLast("ChunkedWriteHandler", new ChunkedWriteHandler());
                            socketChannel.pipeline().addLast("streamServerHandler",
                                    new StreamServerGroupHandler(getHandle()));
                        }
                    });
                    serverFuture = serverBootstrap.bind().sync();
                    serverFuture.await(4000);
                    logger.info("IpCamera file server for a group of cameras has started on port {} for all NIC's.",
                            serverPort);
                    updateState(CHANNEL_STREAM_URL,
                            new StringType("http://" + hostIp + ":" + serverPort + "/ipcamera.mjpeg"));
                    updateState(CHANNEL_HLS_URL,
                            new StringType("http://" + hostIp + ":" + serverPort + "/ipcamera.m3u8"));
                    updateState(CHANNEL_IMAGE_URL,
                            new StringType("http://" + hostIp + ":" + serverPort + "/ipcamera.jpg"));
                } catch (Exception e) {
                    logger.error(
                            "Exception occured when starting the streaming server. Try changing the SERVER_PORT to another number: {}",
                            e);
                }
            }
        }
    }

    void addCamera(String UniqueID) {
        if (IpCameraHandler.listOfOnlineCameraUID.contains(UniqueID)) {
            for (IpCameraHandler handler : IpCameraHandler.listOfOnlineCameraHandlers) {
                if (handler.getThing().getUID().getId().equals(UniqueID)) {
                    if (!cameraOrder.contains(handler)) {
                        logger.info("Adding {} to a camera group.", UniqueID);
                        if (hlsTurnedOn) {
                            logger.info("Starting HLS for the new camera.");
                            handler.handleCommand(handler.getThing().getChannel(CHANNEL_START_STREAM).getUID(),
                                    OnOffType.valueOf("ON"));
                        }
                        cameraOrder.add(handler);
                    }
                }
            }
        }
    }

    // Event based. This is called as each camera comes online after the group handler is registered.
    public void cameraOnline(String uid) {
        logger.debug("New camera {} came online, checking if part of this group", uid);
        if (config.get(CONFIG_FIRST_CAM).equals(uid)) {
            addCamera(uid);
        } else if (config.get(CONFIG_SECOND_CAM).equals(uid)) {
            addCamera(uid);
        } else if (config.get(CONFIG_THIRD_CAM).equals(uid)) {
            addCamera(uid);
        } else if (config.get(CONFIG_FORTH_CAM).equals(uid)) {
            addCamera(uid);
        }
    }

    // Event based. This is called as each camera comes online after the group handler is registered.
    public void cameraOffline(IpCameraHandler handle) {
        if (cameraOrder.remove(handle)) {
            logger.info("Camera {} is now offline, now removed from this group.", handle.getThing().getUID().getId());
        }
    }

    boolean addIfOnline(String UniqueID) {
        if (IpCameraHandler.listOfOnlineCameraUID.contains(UniqueID)) {
            addCamera(UniqueID);
            return true;
        }
        return false;
    }

    void createCameraOrder() {
        addIfOnline(config.get(CONFIG_FIRST_CAM).toString());
        addIfOnline(config.get(CONFIG_SECOND_CAM).toString());
        if (config.get(CONFIG_THIRD_CAM) != null) {
            addIfOnline(config.get(CONFIG_THIRD_CAM).toString());
        }
        if (config.get(CONFIG_FORTH_CAM) != null) {
            addIfOnline(config.get(CONFIG_FORTH_CAM).toString());
        }
        // Cameras can now send events of when they go on and offline.
        IpCameraHandler.listOfGroupHandlers.add(this);
    }

    int checkForMotion(int nextCamerasIndex) {
        int checked = 0;
        for (int index = nextCamerasIndex; checked < cameraOrder.size(); checked++) {
            if (cameraOrder.get(index).motionDetected) {
                logger.debug("Motion detected on a camera in a group and the display order has changed");
                return index;
            }
            if (++index >= cameraOrder.size()) {
                index = 0;
            }
        }
        return nextCamerasIndex;
    }

    Runnable pollingCameraGroup = new Runnable() {
        @Override
        public void run() {

            if (cameraOrder.isEmpty()) {
                createCameraOrder();
            }
            if (++cameraIndex >= cameraOrder.size()) {
                cameraIndex = 0;
                if (mediaSequence > 2147000000) {
                    mediaSequence = 0;
                }
            }
            if (motionChangesOrder) {
                cameraIndex = checkForMotion(cameraIndex);
            }
            if (hlsTurnedOn) {
                setPlayList();
            }
        }
    };

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (!"REFRESH".equals(command.toString())) {
            switch (channelUID.getId()) {
                case CHANNEL_START_STREAM:
                    if ("ON".equals(command.toString())) {
                        logger.info("Starting HLS generation for all cameras in group.");
                        hlsTurnedOn = true;
                        for (IpCameraHandler handler : cameraOrder) {
                            handler.handleCommand(handler.getThing().getChannel(CHANNEL_START_STREAM).getUID(),
                                    OnOffType.valueOf("ON"));
                        }
                    } else {
                        // do we turn all off or do we remember the state before we turned them all on?
                        hlsTurnedOn = false;
                    }
            }
        }
    }

    @Override
    public void initialize() {
        logger.debug("initialize() called for a group camera thing.");
        config = thing.getConfiguration();
        serverPort = Integer.parseInt(config.get(CONFIG_SERVER_PORT).toString());
        pollTimeInSeconds = new BigDecimal(config.get(CONFIG_POLL_CAMERA_MS).toString());
        motionChangesOrder = (boolean) config.get(CONFIG_MOTION_CHANGES_ORDER);
        pollTimeInSeconds = pollTimeInSeconds.divide(new BigDecimal(1000), 1, RoundingMode.HALF_UP);
        if (serverPort == -1) {
            logger.warn("The SERVER_PORT = -1 which disables a lot of features. See readme for more info.");
        } else if (serverPort < 1025) {
            logger.warn("The SERVER_PORT is <= 1024 and may cause permission errors under Linux, try a higher port.");
        }
        if (!"-1".contentEquals(config.get(CONFIG_SERVER_PORT).toString())) {
            startStreamServer(true);
        } else {
            logger.warn("SERVER_PORT is -1 which disables all serving features of the camera group.");
        }
        updateStatus(ThingStatus.ONLINE);
        pollCameraGroupJob = pollCameraGroup.scheduleAtFixedRate(pollingCameraGroup, 10000,
                Integer.parseInt(config.get(CONFIG_POLL_CAMERA_MS).toString()), TimeUnit.MILLISECONDS);
    }

    @Override
    public void dispose() {
        logger.info("dispose() called for a group thing.");
        startStreamServer(false);
        IpCameraHandler.listOfGroupHandlers.remove(this);
        if (pollCameraGroupJob != null) {
            pollCameraGroupJob.cancel(true);
            pollCameraGroupJob = null;
        }
        cameraOrder.clear();
    }
}
