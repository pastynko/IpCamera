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

import be.teletask.onvif.models.OnvifMediaProfile;
import be.teletask.onvif.models.OnvifType;
import be.teletask.onvif.requests.OnvifRequest;

/**
 * The {@link GetSnapshotUri} is responsible for handling onvif snapshot uri commands.
 *
 * @author Matthew Skinner - Initial contribution
 */

@NonNullByDefault
public class GetSnapshotUri implements OnvifRequest {

    String profileToken = "1";

    public GetSnapshotUri() {

    }

    public GetSnapshotUri(OnvifMediaProfile onvifMediaProfile) {
        profileToken = onvifMediaProfile.getToken();
    }

    @Override
    public String getXml() {
        return "<GetSnapshotUri xmlns=\"http://www.onvif.org/ver10/media/wsdl\"> \n" + "\n" + "\n" + "\n" + "\n"
                + "            <ProfileToken>" + profileToken + "</ProfileToken> \n" + "\n" + "\n" + "\n" + "\n"
                + "        </GetSnapshotUri>";
    }

    @Override
    public OnvifType getType() {
        return OnvifType.CUSTOM;
    }

    public static String getParsedResult(String result) {
        int beginIndex = result.indexOf("<tt:Uri>"); // 8 char long
        int endIndex = result.indexOf("</tt:Uri>");
        if (beginIndex >= 0 && endIndex >= 0) {
            return result.substring(beginIndex + 8, endIndex);
        } else {
            return "noUri";
        }
    }
}