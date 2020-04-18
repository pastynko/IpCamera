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

/**
 * The {@link IpCameraDiscoveryService} is responsible for finding globes
 * and setting them up for the handlers.
 *
 * @author Matthew Skinner - Initial contribution
 */

package org.openhab.binding.ipcamera.internal;

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CONFIG_IPADDRESS;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.teletask.onvif.DiscoveryManager;
import be.teletask.onvif.listeners.DiscoveryListener;
import be.teletask.onvif.models.Device;

/**
 * The {@link IpCameraDiscoveryService} is responsible for auto finding cameras that have Onvif
 *
 * @author Matthew Skinner - Initial contribution
 */

// @NonNullByDefault
@SuppressWarnings("null")
@Component(service = DiscoveryService.class, immediate = true, configurationPid = "binding.ipcamera")
public class IpCameraDiscoveryService extends AbstractDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(IpCameraDiscoveryService.class);
    public @Nullable List<Device> devices2 = null;
    private int numberOfCameras = 0;

    public IpCameraDiscoveryService() {
        super(IpCameraHandler.SUPPORTED_THING_TYPES, 30, false);
    }

    @Override
    protected void startBackgroundDiscovery() {

    };

    @Override
    protected void deactivate() {
        super.deactivate();
    }

    private String getCameraBrand(String hostname) throws IOException {
        URL url = new URL(hostname);
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        try {
            connection.connect();
            logger.debug("Getting stream now");
            String response = IOUtils.toString(connection.getInputStream());
            logger.trace("Camera response:{}", response);
            if (response.contains("amcrest")) {
                return "DAHUA";
            } else if (response.contains("dahua")) {
                return "DAHUA";
            } else if (response.contains("foscam")) {
                return "FOSCAM";
            } else if (response.contains("/doc/page/login.asp")) {
                return "HIKVISION";
            } else if (response.contains("instar")) {
                return "INSTAR";
            }
            return "ONVIF";// generic camera
        } finally {
            logger.debug("Closing inputstream now");
            IOUtils.closeQuietly(connection.getInputStream());
        }
    }

    private void newCameraFound(String brand, String hostname) {
        ThingTypeUID thingtypeuid = new ThingTypeUID("ipcamera", brand);
        ThingUID thingUID = new ThingUID(thingtypeuid, "Camera" + ++numberOfCameras);
        DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID)
                .withProperty(CONFIG_IPADDRESS, hostname.substring(7))
                .withLabel(brand + " Camera" + numberOfCameras + " @ " + hostname.substring(7)).build();
        thingDiscovered(discoveryResult);
    }

    private void findCameras() {

        DiscoveryManager manager = new DiscoveryManager();
        manager.setDiscoveryTimeout(10000);
        manager.discover(new DiscoveryListener() {

            @Override
            public void onDiscoveryStarted() {
                logger.info("IpCameraDiscovery started");
            }

            @Override
            public void onDevicesFound(List<Device> devices) {
                for (Device device : devices) {
                    logger.info("Device found Hostname:{}", device.getHostName());
                    try {
                        String brand;
                        brand = getCameraBrand(device.getHostName());
                        newCameraFound(brand, device.getHostName());
                    } catch (IOException e) {
                        logger.debug("Error trying to fetch cameras login page:{}", e);
                    }
                }
            }
        });
    }

    @Override
    protected void startScan() {
        removeOlderResults(getTimestampOfLastScan());
        numberOfCameras = 0;
        findCameras();
    }
}
