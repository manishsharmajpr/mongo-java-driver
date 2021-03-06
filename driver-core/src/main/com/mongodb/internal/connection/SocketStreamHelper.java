/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.internal.connection;

import com.mongodb.MongoInternalException;
import com.mongodb.connection.SocketSettings;
import com.mongodb.connection.SslSettings;
import jdk.net.ExtendedSocketOptions;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketOption;
import java.util.Arrays;

import static com.mongodb.internal.connection.SslHelper.enableHostNameVerification;
import static com.mongodb.internal.connection.SslHelper.enableSni;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

final class SocketStreamHelper {
    // Keep alive options and their values for Java 11+
    private static final String TCP_KEEPIDLE = "TCP_KEEPIDLE";
    private static final int TCP_KEEPIDLE_DURATION = 300;
    private static final String TCP_KEEPCOUNT = "TCP_KEEPCOUNT";
    private static final int TCP_KEEPCOUNT_LIMIT = 9;
    private static final String TCP_KEEPINTERVAL = "TCP_KEEPINTERVAL";
    private static final int TCP_KEEPINTERVAL_DURATION = 10;

    static void initialize(final Socket socket, final InetSocketAddress inetSocketAddress, final SocketSettings settings,
                           final SslSettings sslSettings) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(settings.getReadTimeout(MILLISECONDS));
        socket.setKeepAlive(true);

        // Adding keep alive options for users of Java 11+. These options will be ignored for older Java versions.
        setExtendedSocketOptions(socket);

        if (settings.getReceiveBufferSize() > 0) {
            socket.setReceiveBufferSize(settings.getReceiveBufferSize());
        }
        if (settings.getSendBufferSize() > 0) {
            socket.setSendBufferSize(settings.getSendBufferSize());
        }
        if (sslSettings.isEnabled() || socket instanceof SSLSocket) {
            if (!(socket instanceof SSLSocket)) {
                throw new MongoInternalException("SSL is enabled but the socket is not an instance of javax.net.ssl.SSLSocket");
            }
            SSLSocket sslSocket = (SSLSocket) socket;
            SSLParameters sslParameters = sslSocket.getSSLParameters();
            if (sslParameters == null) {
                sslParameters = new SSLParameters();
            }

            enableSni(inetSocketAddress.getHostName(), sslParameters);

            if (!sslSettings.isInvalidHostNameAllowed()) {
                enableHostNameVerification(sslParameters);
            }
            sslSocket.setSSLParameters(sslParameters);
        }
        socket.connect(inetSocketAddress, settings.getConnectTimeout(MILLISECONDS));
    }

    @SuppressWarnings("unchecked")
    private static void setExtendedSocketOptions(final Socket socket) {
        if (Arrays.stream(ExtendedSocketOptions.class.getDeclaredFields()).anyMatch(f -> f.getName().equals(TCP_KEEPCOUNT))) {
            try {
                Method setOptionMethod = Socket.class.getMethod("setOption", SocketOption.class, Object.class);
                setOptionMethod.invoke(socket, ExtendedSocketOptions.class.getDeclaredField(TCP_KEEPCOUNT).get(null),
                        TCP_KEEPCOUNT_LIMIT);
                setOptionMethod.invoke(socket, ExtendedSocketOptions.class.getDeclaredField(TCP_KEEPIDLE).get(null),
                        TCP_KEEPIDLE_DURATION);
                setOptionMethod.invoke(socket, ExtendedSocketOptions.class.getDeclaredField(TCP_KEEPINTERVAL).get(null),
                        TCP_KEEPINTERVAL_DURATION);
            } catch (Throwable t) {
            }
        }
    }

    private SocketStreamHelper() {
    }
}
