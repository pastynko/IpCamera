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

package org.openhab.binding.ipcamera.onvif;

import org.eclipse.jdt.annotation.NonNullByDefault;

import be.teletask.onvif.models.OnvifType;
import be.teletask.onvif.requests.OnvifRequest;

/**
 * The {@link EventsRequest} is responsible for handling Onvif events and alarms which are not fully implemented or
 * tested yet.
 *
 * @author Matthew Skinner - Initial contribution
 */

@NonNullByDefault
public class EventsRequest implements OnvifRequest {
    String profileToken;
    String requestType;
    String eventAddress = "";// todo implement this again.

    public EventsRequest(String requestType, String profileToken) {
        this.requestType = requestType;
        this.profileToken = profileToken;
    }

    @Override
    public String getXml() {
        switch (requestType) {
            case "CreatePullPointSubscription":// works
                return "<CreatePullPointSubscription xmlns=\"http://www.onvif.org/ver10/events/wsdl\"></CreatePullPointSubscription>";
            case "PullMessagesRequest":// needs extra stuff added to work, below is only 120 seconds before timeout.
                return "<Header><Action> http://www.onvif.org/ver10/events/wsdl/PullPointSubscription/PullMessagesRequest</Action><To>"
                        + eventAddress
                        + "</To></Header><Body><PullMessagesRequest xmlns=\"http://www.onvif.org/ver10/events/wsdl\"><Timeout>PT120S</Timeout><MessageLimit>20</MessageLimit></PullMessagesRequest></Body>";
            case "GetEventProperties": // my cams report it is not supported.
                return "<GetEventProperties xmlns=\"http://www.onvif.org/ver10/events/wsdl\"></GetEventProperties>";
        }
        return "notfound";
    }

    @Override
    public OnvifType getType() {
        return OnvifType.CUSTOM;
    }
}
