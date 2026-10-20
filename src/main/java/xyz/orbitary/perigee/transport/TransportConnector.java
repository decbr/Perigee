/*
 * This file is part of a Bit libraries package.
 * Licensed under the GNU Lesser General Public License v3.0.
 *
 * Copyright (c) 2023-2026 ImBit
 */

package xyz.orbitary.perigee.transport;

import java.io.IOException;
import java.util.List;


/**
 * Opens the first reachable endpoint from a candidate list.
 */
public final class TransportConnector {
    private TransportConnector() {}

    public static Transport connect(List<String> endpoints, boolean windows) throws IOException {
        IOException failure = new IOException("No Discord IPC endpoint is reachable; is the Discord client running?");
        for (String endpoint : endpoints) {
            try {
                return windows ? WindowsPipeTransport.connect(endpoint) : UnixSocketTransport.connect(endpoint);
            } catch (IOException | RuntimeException e) {
                failure.addSuppressed(new IOException(endpoint + ": " + e, e));
            }
        }
        throw failure;
    }

    public static Transport connect() throws IOException {
        return connect(EndpointResolver.resolve(), EndpointResolver.isWindows());
    }

}
