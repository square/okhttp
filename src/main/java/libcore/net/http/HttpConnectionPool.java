/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package libcore.net.http;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import libcore.util.Libcore;

/**
 * A pool of HTTP and SPDY connections. This class exposes its tuning parameters
 * as system properties:
 * <ul>
 *   <li>{@code http.keepAlive} true if HTTP and SPDY connections should be
 *       pooled at all. Default is true.
 *   <li>{@code http.maxConnections} maximum number of connections to each host.
 *       Default is 5.
 * </ul>
 *
 * <p>This class <i>doesn't</i> adjust its configuration as system properties
 * are changed. This assumes that the applications that set these parameters do
 * so before making HTTP connections, and that this class is initialized lazily.
 */
final class HttpConnectionPool {
    public static final HttpConnectionPool INSTANCE = new HttpConnectionPool();

    private final int maxConnections;
    private final HashMap<HttpConnection.Address, List<HttpConnection>> connectionPool
            = new HashMap<HttpConnection.Address, List<HttpConnection>>();

    private HttpConnectionPool() {
        String keepAlive = System.getProperty("http.keepAlive");
        if (keepAlive != null && !Boolean.parseBoolean(keepAlive)) {
            maxConnections = 0;
            return;
        }

        String maxConnectionsString = System.getProperty("http.maxConnections");
        this.maxConnections = maxConnectionsString != null
                ? Integer.parseInt(maxConnectionsString)
                : 5;
    }

    public HttpConnection get(HttpConnection.Address address, int connectTimeout)
            throws IOException {
        // First try to reuse an existing HTTP connection.
        synchronized (connectionPool) {
            List<HttpConnection> connections = connectionPool.get(address);
            while (connections != null) {
                HttpConnection connection = connections.get(connections.size() - 1);
                if (!connection.isSpdy()) {
                    connections.remove(connections.size() - 1);
                }
                if (connections.isEmpty()) {
                    connectionPool.remove(address);
                    connections = null;
                }
                if (connection.isEligibleForRecycling()) {
                    // Since Socket is recycled, re-tag before using
                    Socket socket = connection.getSocket();
                    Libcore.tagSocket(socket);
                    return connection;
                }
            }
        }

        /*
         * We couldn't find a reusable connection, so we need to create a new
         * connection. We're careful not to do so while holding a lock!
         */
        return address.connect(connectTimeout);
    }

    /**
     * Gives the HTTP/HTTPS connection to the pool. It is an error to use {@code
     * connection} after calling this method.
     */
    public void recycle(HttpConnection connection) {
        if (connection.isSpdy()) {
            throw new IllegalArgumentException();
        }

        Socket socket = connection.getSocket();
        try {
            Libcore.untagSocket(socket);
        } catch (SocketException e) {
            // When unable to remove tagging, skip recycling and close
            Libcore.logW("Unable to untagSocket(): " + e);
            connection.closeSocketAndStreams();
            return;
        }

        if (maxConnections > 0 && connection.isEligibleForRecycling()) {
            HttpConnection.Address address = connection.getAddress();
            synchronized (connectionPool) {
                List<HttpConnection> connections = connectionPool.get(address);
                if (connections == null) {
                    connections = new ArrayList<HttpConnection>();
                    connectionPool.put(address, connections);
                }
                if (connections.size() < maxConnections) {
                    connection.setRecycled();
                    connections.add(connection);
                    return; // keep the connection open
                }
            }
        }

        // don't close streams while holding a lock!
        connection.closeSocketAndStreams();
    }

    /**
     * Shares the SPDY connection with the pool. Callers to this method may
     * continue to use {@code connection}.
     */
    public void share(HttpConnection connection) {
        if (!connection.isSpdy()) {
            throw new IllegalArgumentException();
        }
        if (maxConnections <= 0 || !connection.isEligibleForRecycling()) {
            return;
        }
        HttpConnection.Address address = connection.getAddress();
        synchronized (connectionPool) {
            List<HttpConnection> connections = connectionPool.get(address);
            if (connections == null) {
                connections = new ArrayList<HttpConnection>(1);
                connections.add(connection);
                connectionPool.put(address, connections);
            }
        }
    }
}
