/**
 * Copyright (c) 2014,2018 by the respective copyright holders.
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.ipcamera.internal;

import java.security.MessageDigest;
import java.util.Random;

import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponse;

/**
 * The {@link MyNettyAuthHandler} is responsible for handling the basic and digest auths
 *
 *
 * @author Matthew Skinner - Initial contribution
 */

public class MyNettyAuthHandler extends ChannelDuplexHandler {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private IpCameraHandler myHandler;
    private String username, password;
    private String httpMethod, httpUrl;
    private byte ncCounter = 0;
    String nonce = "empty", opaque = "empty", qop = "empty", realm = "empty";

    public MyNettyAuthHandler(String user, String pass, String method, String url, ThingHandler handle) {
        myHandler = (IpCameraHandler) handle;
        username = user;
        password = pass;
        httpUrl = url;
        httpMethod = method;
    }

    public MyNettyAuthHandler(String user, String pass, ThingHandler handle) {
        myHandler = (IpCameraHandler) handle;
        username = user;
        password = pass;
    }

    public void setURL(String url) {
        httpMethod = "GET";
        httpUrl = url;
        logger.debug("Url is set in authHandler:{}", url);
    }

    private String calcMD5Hash(String toHash) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            byte[] array = messageDigest.digest(toHash.getBytes());
            StringBuffer stringBuffer = new StringBuffer();
            for (int i = 0; i < array.length; ++i) {
                stringBuffer.append(Integer.toHexString((array[i] & 0xFF) | 0x100).substring(1, 3));
            }
            messageDigest = null;
            array = null;
            return stringBuffer.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            logger.error("NoSuchAlgorithmException error when calculating MD5 hash");
        }
        return null;
    }

    private String searchString(String rawString, String searchedString) {
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
        return null;
    }

    // Method can be used a few ways. processAuth(null, string,string, false) to return the digest on demand, and
    // processAuth(challString, string,string, true) to auto send new packet
    // First run it should not have authenticate as null
    // nonce is reused if authenticate is null so the NC needs to increment to allow this//
    public String processAuth(String authenticate, String httpMethod, String requestURI, boolean reSend,
            boolean useNewChannel) {

        if (authenticate != null) {

            if (authenticate.contains("Basic realm=\"")) {
                if (myHandler.useDigestAuth == true) {
                    return "Error:Downgrade authenticate avoided";
                }
                logger.debug("Setting up the camera to use Basic Auth and resending last request with correct auth.");
                myHandler.setBasicAuth(true);
                myHandler.sendHttpRequest(httpMethod, requestURI, null, false);
                return "Using Basic";
            }

            /////// Fresh Digest Authenticate method follows as Basic is already handled and returned ////////
            realm = searchString(authenticate, "realm=\"");
            if (realm == null) {
                logger.warn("Could not find a valid WWW-Authenticate response in :{}", authenticate);
                return "Error";
            }
            nonce = searchString(authenticate, "nonce=\"");
            opaque = searchString(authenticate, "opaque=\"");
            qop = searchString(authenticate, "qop=\"");

            if (!qop.isEmpty() && !realm.isEmpty()) {
                myHandler.useDigestAuth = true;
            } else {
                logger.warn("Something is missing? opaque:{}, qop:{}, realm:{}", opaque, qop, realm);
            }

            String stale = searchString(authenticate, "stale=\"");
            if (stale == null) {
            } else if ("false".equals(stale)) {
                logger.debug(
                        "Camera reported stale=false which normally means an issue with the username or password.");
            } else if ("true".equals(stale)) {
                logger.debug("Camera reported stale=true which normally means the NONCE has expired.");
            }
        }

        // create the MD5 hashes
        String ha1 = username + ":" + realm + ":" + password;
        ha1 = calcMD5Hash(ha1);
        Random random = new Random();
        String cnonce = Integer.toHexString(random.nextInt());
        random = null;
        ncCounter = (ncCounter > 125) ? 1 : ++ncCounter;
        String nc = String.format("%08X", ncCounter); // 8 digit hex number
        String ha2 = httpMethod + ":" + requestURI;
        ha2 = calcMD5Hash(ha2);

        String response = ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2;
        response = calcMD5Hash(response);

        String digestString = "username=\"" + username + "\", realm=\"" + realm + "\", nonce=\"" + nonce + "\", uri=\""
                + requestURI + "\", cnonce=\"" + cnonce + "\", nc=" + nc + ", qop=\"" + qop + "\", response=\""
                + response + "\", opaque=\"" + opaque + "\"";
        // logger.debug("digest string is this {}", digestString);
        if (reSend) {
            myHandler.sendHttpRequest(httpMethod, requestURI, digestString, useNewChannel);
            return null;
        }

        return digestString;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean closeConnection = false;
        String authenticate = null;
        if (msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) msg;
            if (response.status().code() == 401) {
                logger.debug("401: This is normal for digest authentication. Request is {}:{}", httpMethod, httpUrl);
                if (!response.headers().isEmpty()) {
                    for (CharSequence name : response.headers().names()) {
                        for (CharSequence value : response.headers().getAll(name)) {
                            if (name.toString().equals("WWW-Authenticate")) {
                                authenticate = value.toString();
                            }
                            if (name.toString().equals("Connection")) {
                                if (value.toString().contains("close")) {
                                    closeConnection = true;
                                }
                            }
                        }
                    }
                    if (authenticate != null) {
                        processAuth(authenticate, httpMethod, httpUrl, true, closeConnection);
                    }
                    if (closeConnection) {
                        // logger.debug("401: Connection closing.");
                        ctx.close();// needs to be here
                    }
                }
            }
        }
        // Pass the Message back to the pipeline for the next handler to process//
        super.channelRead(ctx, msg);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        // logger.debug("++++++++ Auth Handler created ++++++++ {}", httpUrl);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        myHandler = null;
        username = password = httpMethod = httpUrl = null;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.debug(
                "Camera may have closed the connection which can be normal. Do not report this unless it happens to every request. Cause reported is:{}",
                cause);
        ctx.close();
    }
}
