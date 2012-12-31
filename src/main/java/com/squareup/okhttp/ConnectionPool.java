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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.io.IoUtils;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Manages reuse of HTTP and SPDY connections for reduced network latency. HTTP
 * requests that share the same {@link Address} may share a {@link Connection}.
 * This class implements the policy of which connections to keep open for future
 * use.
 *
 * <p>The {@link #getDefault() system-wide default} uses system properties for
 * tuning parameters:
 * <ul>
 *   <li>{@code http.keepAlive} true if HTTP and SPDY connections should be
 *       pooled at all. Default is true.
 *   <li>{@code http.maxConnections} maximum number of connections to each
 *       address. Default is 5.
 * </ul>
 *
 * <p>The default instance <i>doesn't</i> adjust its configuration as system
 * properties are changed. This assumes that the applications that set these
 * parameters do so before making HTTP connections, and that this class is
 * initialized lazily.
 */
public final class ConnectionPool {
    private static final ConnectionPool systemDefault;
    static {
        String keepAlive = System.getProperty("http.keepAlive");
        String maxConnections = System.getProperty("http.maxConnections");
        if (keepAlive != null && !Boolean.parseBoolean(keepAlive)) {
            systemDefault = new ConnectionPool(0);
        } else if (maxConnections != null) {
            systemDefault = new ConnectionPool(Integer.parseInt(maxConnections));
        } else {
            systemDefault = new ConnectionPool(5);
        }
    }

    /** The maximum number of idle connections for each address. */
    private final int maxConnections;
    private final HashMap<Address, List<Connection>> connectionPool
            = new HashMap<Address, List<Connection>>();

    public ConnectionPool(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public static ConnectionPool getDefault() {
        return systemDefault;
    }

    /**
     * Returns a recycled connection to {@code address}, or null if no such
     * connection exists.
     */
    public Connection get(Address address) {
        // First try to reuse an existing HTTP connection.
        synchronized (connectionPool) {
            List<Connection> connections = connectionPool.get(address);
            while (connections != null) {
                Connection connection = connections.get(connections.size() - 1);
                if (!connection.isSpdy()) {
                    connections.remove(connections.size() - 1);
                }
                if (connections.isEmpty()) {
                    connectionPool.remove(address);
                    connections = null;
                }
                if (!connection.isEligibleForRecycling()) {
                    IoUtils.closeQuietly(connection);
                    continue;
                }
                try {
                    Platform.get().tagSocket(connection.getSocket());
                } catch (SocketException e) {
                    // When unable to tag, skip recycling and close
                    Platform.get().logW("Unable to tagSocket(): " + e);
                    IoUtils.closeQuietly(connection);
                    continue;
                }
                return connection;
            }
        }
        return null;
    }

    /**
     * Gives {@code connection} to the pool. The pool may store the connection,
     * or close it, as its policy describes.
     *
     * <p>It is an error to use {@code connection} after calling this method.
     */
    public void recycle(Connection connection) {
        if (connection.isSpdy()) {
            return;
        }

        try {
            Platform.get().untagSocket(connection.getSocket());
        } catch (SocketException e) {
            // When unable to remove tagging, skip recycling and close
            Platform.get().logW("Unable to untagSocket(): " + e);
            IoUtils.closeQuietly(connection);
            return;
        }

        if (maxConnections > 0 && connection.isEligibleForRecycling()) {
            Address address = connection.getAddress();
            synchronized (connectionPool) {
                List<Connection> connections = connectionPool.get(address);
                if (connections == null) {
                    connections = new ArrayList<Connection>();
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
        IoUtils.closeQuietly(connection);
    }

    /**
     * Shares the SPDY connection with the pool. Callers to this method may
     * continue to use {@code connection}.
     */
    public void share(Connection connection) {
        if (!connection.isSpdy()) {
            throw new IllegalArgumentException();
        }
        if (maxConnections <= 0 || !connection.isEligibleForRecycling()) {
            return;
        }
        Address address = connection.getAddress();
        synchronized (connectionPool) {
            List<Connection> connections = connectionPool.get(address);
            if (connections == null) {
                connections = new ArrayList<Connection>(1);
                connections.add(connection);
                connectionPool.put(address, connections);
            }
        }
    }
}
