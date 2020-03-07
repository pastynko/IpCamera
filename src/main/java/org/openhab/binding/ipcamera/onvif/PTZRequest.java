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

package org.openhab.binding.ipcamera.onvif;

import java.util.LinkedList;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.teletask.onvif.OnvifManager;
import be.teletask.onvif.listeners.OnvifResponseListener;
import be.teletask.onvif.models.OnvifDevice;
import be.teletask.onvif.models.OnvifType;
import be.teletask.onvif.requests.OnvifRequest;
import be.teletask.onvif.responses.OnvifResponse;

/**
 * The {@link PTZRequest} is responsible for handling onvif PTZ commands.
 *
 * @author Matthew Skinner - Initial contribution
 */

@NonNullByDefault
public class PTZRequest implements OnvifRequest {
    // These hold the cameras PTZ position in the range that the camera uses, ie
    // mine is -1 to +1
    private Float panRangeMin = -1.0f;
    private Float panRangeMax = 1.0f;
    private Float tiltRangeMin = -1.0f;
    private Float tiltRangeMax = 1.0f;
    private Float zoomMin = 0.0f;
    private Float zoomMax = 1.0f;
    // These hold the PTZ values for updating Openhabs controls in 0-100 range
    private Float currentPanPercentage = 0.0f;
    private Float currentTiltPercentage = 0.0f;
    private Float currentZoomPercentage = 0.0f;
    private Float currentPanCamValue = 0.0f;
    public Float currentTiltCamValue = 0.0f;
    public Float currentZoomCamValue = 0.0f;
    public String ptzNodeToken = "000";
    public String ptzConfigToken = "000";
    public final Logger logger = LoggerFactory.getLogger(getClass());
    String mediaProfileToken = "Profile1";
    int mediaProfileIndex = 0;
    int presetTokenIndex = 0;
    LinkedList<String> presetTokens = new LinkedList<String>();
    String requestType = "GetConfigurations";
    OnvifManager ptzManager = new OnvifManager();
    boolean ptzDevice = false;
    private OnvifDevice thisOnvifCamera;

    public PTZRequest(OnvifManager ptzManager, OnvifDevice thisOnvifCamera, String mediaProfileToken) {
        this.ptzManager = ptzManager;
        this.thisOnvifCamera = thisOnvifCamera;
        this.mediaProfileToken = mediaProfileToken;
        setupListener();
        sendRequest("GetNodes");
        ptzDevice = true;
    }

    public PTZRequest(String request) {
        requestType = request;
        this.thisOnvifCamera = new OnvifDevice("none");
    }

    public boolean supportsPTZ() {
        return ptzDevice;
    }

    LinkedList<String> listOfResults(String search, String toFind) {
        LinkedList<String> results = new LinkedList<String>();
        for (int beginIndex = 0, endIndex = 0; beginIndex != -1;) {
            beginIndex = search.indexOf(toFind, beginIndex);
            if (beginIndex >= 0) {
                endIndex = search.indexOf("\"", (beginIndex + toFind.length()));
                if (endIndex >= 0) {
                    logger.debug("String was found:{}", search.substring(beginIndex + toFind.length(), endIndex));
                    results.add(search.substring(beginIndex + 14, endIndex));
                }
                ++beginIndex;
            } else {
                logger.debug("no more to find");
            }
        }
        return results;
    }

    public void gotoPreset(int index) {
        if (index > 0) {// 0 is reserved for HOME as cameras seem to start at preset 1.
            if (presetTokens.isEmpty()) {
                logger.warn("Camera did not report any presets to the binding");
            } else {
                presetTokenIndex = index - 1;
                sendRequest("GotoPreset");
            }
        }
    }

    // may need to make requestType thread safe if multiple reply trigger a SOAP to be sent at the same time.
    private void setupListener() {
        ptzManager.setOnvifResponseListener(new OnvifResponseListener() {
            @Override
            public void onResponse(@Nullable OnvifDevice thisOnvifCamera, @Nullable OnvifResponse response) {
                if (response == null) {
                    return;
                }
                logger.debug("We got an ONVIF ptz response:{}", response.getXml());
                if (response.getXml().contains("GetStatusResponse")) {
                    processPTZLocation(response.getXml());
                } else if (response.getXml().contains("GetPresetsResponse")) {
                    // collectPrestTokens(response.getXml());
                    presetTokens = listOfResults(response.getXml(), "Preset token=\"");
                } else if (response.getXml().contains("GetConfigurationsResponse")) {
                    sendRequest("GetPresets");
                    ptzConfigToken = searchString(response.getXml(), "PTZConfiguration token=\"");
                    logger.debug("ptzConfigToken={}", ptzConfigToken);
                    sendRequest("GetConfigurationOptions");
                    // sendRequest("AddPTZConfiguration");
                    // sendRequest("SetConfiguration");
                } else if (response.getXml().contains("GetNodesResponse")) {
                    sendRequest("GetStatus");
                    ptzNodeToken = searchString(response.getXml(), "token=\"");
                    logger.debug("ptzNodeToken={}", ptzNodeToken);
                    sendRequest("GetConfigurations");
                }
            }

            @Override
            public void onError(@Nullable OnvifDevice thisOnvifCamera, int errorCode, @Nullable String errorMessage) {
                logger.debug("We got a ONVIF PTZ ERROR {}:{}", errorCode, errorMessage);
            }
        });
    }

    public void getStatus() {
        sendRequest("GetStatus");
    }

    public Float getAbsolutePan() {
        return currentPanPercentage;
    }

    public Float getAbsoluteTilt() {
        return currentTiltPercentage;
    }

    public Float getAbsoluteZoom() {
        return currentZoomPercentage;
    }

    public void setAbsolutePan(Float panValue) {// Value is 0-100% of cameras range
        if (supportsPTZ()) {
            currentPanPercentage = panValue;
            currentPanCamValue = ((((panRangeMin - panRangeMax) * -1) / 100) * panValue + panRangeMin);
        }
    }

    public void setAbsoluteTilt(Float tiltValue) {// Value is 0-100% of cameras range
        if (supportsPTZ()) {
            currentTiltPercentage = tiltValue;
            currentTiltCamValue = ((((panRangeMin - panRangeMax) * -1) / 100) * tiltValue + tiltRangeMin);
        }
    }

    public void setAbsoluteZoom(Float zoomValue) {// Value is 0-100% of cameras range
        if (supportsPTZ()) {
            currentZoomPercentage = zoomValue;
            currentZoomCamValue = ((((zoomMin - zoomMax) * -1) / 100) * zoomValue + zoomMin);
        }
    }

    public void absoluteMove() { // Camera wont move until PTZ values are set, then call this.
        sendRequest("AbsoluteMove");
    }

    void processPTZLocation(String result) {
        logger.debug("Process new PTZ location now");

        int beginIndex = result.indexOf("x=\"");
        int endIndex = result.indexOf("\"", (beginIndex + 3));
        if (beginIndex >= 0 && endIndex >= 0) {
            currentPanCamValue = Float.parseFloat(result.substring(beginIndex + 3, endIndex));
            currentPanPercentage = (((panRangeMin - currentPanCamValue) * -1) / ((panRangeMin - panRangeMax) * -1))
                    * 100;
            logger.debug("Pan is updating to:{} and the cam value is {}", Math.round(currentPanPercentage),
                    currentPanCamValue);
        } else {
            logger.warn("turning off PTZ functions as binding could not determin current PTZ locations.");
            ptzDevice = false;
            return;
        }

        beginIndex = result.indexOf("y=\"");
        endIndex = result.indexOf("\"", (beginIndex + 3));
        if (beginIndex >= 0 && endIndex >= 0) {
            currentTiltCamValue = Float.parseFloat(result.substring(beginIndex + 3, endIndex));
            currentTiltPercentage = (((tiltRangeMin - currentTiltCamValue) * -1) / ((tiltRangeMin - tiltRangeMax) * -1))
                    * 100;
            logger.debug("Tilt is updating to:{} and the cam value is {}", Math.round(currentTiltPercentage),
                    currentTiltCamValue);
        } else {
            logger.warn("turning off PTZ functions as binding could not determin current PTZ locations.");
            ptzDevice = false;
            return;
        }

        beginIndex = result.lastIndexOf("x=\"");
        endIndex = result.indexOf("\"", (beginIndex + 3));
        if (beginIndex >= 0 && endIndex >= 0) {
            currentZoomCamValue = Float.parseFloat(result.substring(beginIndex + 3, endIndex));
            currentZoomPercentage = (((zoomMin - currentZoomCamValue) * -1) / ((zoomMin - zoomMax) * -1)) * 100;
            logger.debug("Zoom is updating to:{} and the cam value is {}", Math.round(currentZoomPercentage),
                    currentZoomCamValue);
        } else {
            logger.warn("turning off PTZ functions as binding could not determin current PTZ locations.");
            ptzDevice = false;
            return;
        }
        ptzDevice = true;
    }

    @Override
    public String getXml() {
        switch (requestType) {
            case "GetConfigurations":
                return "<GetConfigurations xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\"></GetConfigurations>";
            case "GetConfigurationOptions":
                return "<GetConfigurationOptions xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\"><ConfigurationToken>"
                        + ptzConfigToken + "</ConfigurationToken></GetConfigurationOptions>";
            case "GetConfiguration":
                return "<GetConfiguration xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\"><PTZConfigurationToken>"
                        + ptzConfigToken + "</PTZConfigurationToken></GetConfiguration>";
            case "SetConfiguration":// not tested to work yet
                return "<SetConfiguration xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\"><PTZConfiguration><NodeToken>"
                        + ptzNodeToken
                        + "</NodeToken><DefaultAbsolutePantTiltPositionSpace>AbsolutePanTiltPositionSpace</DefaultAbsolutePantTiltPositionSpace><DefaultAbsoluteZoomPositionSpace>AbsoluteZoomPositionSpace</DefaultAbsoluteZoomPositionSpace></PTZConfiguration></SetConfiguration>";
            case "AbsoluteMove":
                return "<AbsoluteMove xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\"><ProfileToken>" + mediaProfileToken
                        + "</ProfileToken><Position><PanTilt x=\"" + currentPanCamValue + "\" y=\""
                        + currentTiltCamValue
                        + "\" space=\"http://www.onvif.org/ver10/tptz/PanTiltSpaces/PositionGenericSpace\">\n"
                        + "</PanTilt>\n" + "<Zoom x=\"" + currentZoomCamValue
                        + "\" space=\"http://www.onvif.org/ver10/tptz/ZoomSpaces/PositionGenericSpace\">\n"
                        + "</Zoom>\n" + "</Position>\n"
                        + "<Speed><PanTilt x=\"0.1\" y=\"0.1\" space=\"http://www.onvif.org/ver10/tptz/PanTiltSpaces/GenericSpeedSpace\"></PanTilt><Zoom x=\"1.0\" space=\"http://www.onvif.org/ver10/tptz/ZoomSpaces/ZoomGenericSpeedSpace\"></Zoom>\n"
                        + "</Speed></AbsoluteMove>";
            case "GetNodes":
                return "<GetNodes xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\"></GetNodes>";
            case "GetStatus":
                return "<GetStatus xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\"><ProfileToken>" + mediaProfileToken
                        + "</ProfileToken></GetStatus>";
            case "GotoPreset":
                return "<GotoPreset xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\"><ProfileToken>" + mediaProfileToken
                        + "</ProfileToken><PresetToken>" + presetTokens.get(presetTokenIndex)
                        + "</PresetToken><Speed><PanTilt x=\"0.0\" y=\"0.0\" space=\"\"></PanTilt><Zoom x=\"0.0\" space=\"\"></Zoom></Speed></GotoPreset>";
            case "GetPresets":
                return "<GetPresets xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\"><ProfileToken>" + mediaProfileToken
                        + "</ProfileToken></GetPresets>";
            case "GetProfiles":
                return "<GetProfiles xmlns=\"http://www.onvif.org/ver10/media/wsdl\"></GetProfiles>";
            case "AddPTZConfiguration": // not tested to work yet
                return "<AddPTZConfiguration xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\"><ProfileToken>"
                        + mediaProfileToken + "</ProfileToken><ConfigurationToken>" + ptzConfigToken
                        + "</ConfigurationToken></AddPTZConfiguration>";
        }
        return "notfound";
    }

    @Override
    public OnvifType getType() {
        return OnvifType.CUSTOM;
    }

    public void sendRequest(String request) {
        requestType = request;
        ptzManager.sendOnvifRequest(thisOnvifCamera, this);
    }

    public String searchString(String rawString, String searchedString) {
        String result = "";
        int index = 0;
        index = rawString.indexOf(searchedString);
        if (index != -1) // -1 means "not found"
        {
            result = rawString.substring(index + searchedString.length(), rawString.length());
            index = result.indexOf(',');
            if (index == -1) {
                index = result.indexOf('"');
                if (index == -1) {
                    index = result.indexOf('}');
                    if (index == -1) {
                        return result;
                    } else {
                        return result.substring(0, index);
                    }
                } else {
                    return result.substring(0, index);
                }
            } else {
                result = result.substring(0, index);
                index = result.indexOf('"');
                if (index == -1) {
                    return result;
                } else {
                    return result.substring(0, index);
                }
            }
        }
        return "";
    }
}
