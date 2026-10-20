/*
 * This file is part of a Bit libraries package.
 * Licensed under the GNU Lesser General Public License v3.0.
 *
 * Copyright (c) 2023-2026 ImBit
 */

package xyz.orbitary.perigee.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import xyz.orbitary.perigee.protocol.Frame;
import xyz.orbitary.perigee.protocol.FrameCodec;
import xyz.orbitary.perigee.protocol.Opcode;
import xyz.orbitary.perigee.support.UnixTestServer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static xyz.orbitary.perigee.support.Tests.await;


class UnixSocketTransportTest {
    @TempDir
    Path dir;

    private UnixTestServer server;

    @BeforeEach
    void listen() throws IOException {
        server = new UnixTestServer(dir.resolve("discord-ipc-0"));
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
    }

    private Transport connect() throws IOException {
        return UnixSocketTransport.connect(server.socket().toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 500_000})
    void carriesFramesBothWaysThroughTheCodec(int size) throws Exception {
        String payload = "x".repeat(size);
        CompletableFuture<Void> echo = server.echoOnce();

        try (Transport transport = connect()) {
            transport.write(FrameCodec.encode(Opcode.FRAME, payload));
            assertEquals(new Frame(Opcode.FRAME, payload), FrameCodec.decode(transport.input()));
        }
        await(echo);
    }

    @Test
    void theConnectorSkipsUnreachableEndpoints() throws Exception {
        CompletableFuture<Void> echo = server.echoOnce();

        try (Transport transport = TransportConnector.connect(List.of(dir.resolve("missing").toString(), server.socket().toString()), false)) {
            transport.write(FrameCodec.encode(Opcode.HANDSHAKE, "{\"v\":1}"));
            assertEquals(new Frame(Opcode.HANDSHAKE, "{\"v\":1}"), FrameCodec.decode(transport.input()));
        }
        await(echo);
    }

    @Test
    void theConnectorReportsEveryFailureWhenNothingIsListening() {
        IOException e = assertThrows(IOException.class, () -> TransportConnector.connect(List.of(dir.resolve("a").toString(), dir.resolve("b").toString()), false));

        assertEquals(2, e.getSuppressed().length);
        assertTrue(String.valueOf(e.getSuppressed()[0].getMessage()).startsWith(dir.resolve("a") + ": "));
        assertTrue(String.valueOf(e.getSuppressed()[1].getMessage()).startsWith(dir.resolve("b") + ": "));
    }

    @Test
    void aPeerClosingMidFrameSurfacesAsEndOfStream() throws Exception {
        CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
            try (SocketChannel client = server.accept()) {
                client.write(ByteBuffer.wrap(new byte[]{1, 0, 0, 0, 10, 0}));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        try (Transport transport = connect()) {
            assertThrows(EOFException.class, () -> FrameCodec.decode(transport.input()));
        }
        await(peer);
    }

    @Test
    void usingAClosedTransportFails() throws Exception {
        CompletableFuture<SocketChannel> accepted = server.acceptAsync();
        Transport transport = connect();
        SocketChannel peer = await(accepted);

        transport.close();

        assertThrows(IOException.class, () -> transport.write(FrameCodec.encode(Opcode.FRAME, "{}")));
        assertThrows(IOException.class, () -> transport.input().read());
        assertDoesNotThrow(transport::close, "closing twice is harmless");
        peer.close();
    }

    @Test
    void closingFromAnotherThreadUnblocksARead() throws Exception {
        CompletableFuture<SocketChannel> accepted = server.acceptAsync();
        Transport transport = connect();
        SocketChannel peer = await(accepted);
        CompletableFuture<Throwable> blocked = CompletableFuture.supplyAsync(() -> {
            try {
                transport.input().read();
                return new AssertionError("read returned");
            } catch (IOException e) {
                return e;
            }
        });
        Thread.sleep(100);
        assertFalse(blocked.isDone());

        transport.close();

        assertInstanceOf(IOException.class, await(blocked));
        peer.close();
    }

    @Test
    void concurrentWritersNeverInterleaveFrames() throws Exception {
        int writers = 4;
        int framesEach = 200;
        CompletableFuture<List<String>> received = CompletableFuture.supplyAsync(() -> {
            List<String> payloads = new ArrayList<>();
            try (SocketChannel client = server.accept()) {
                InputStream in = Channels.newInputStream(client);
                for (int i = 0; i < writers * framesEach; i++) {
                    payloads.add(FrameCodec.decode(in).payload());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return payloads;
        });

        try (Transport transport = connect()) {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writer = w;
                tasks.add(CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < framesEach; i++) {
                        String payload = writer + ":" + i + ":" + "y".repeat(1 + (i * 37) % 4_000);
                        try {
                            transport.write(FrameCodec.encode(Opcode.FRAME, payload));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                }));
            }
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);

            Set<String> keys = new TreeSet<>();
            for (String payload : received.get(30, TimeUnit.SECONDS)) {
                String[] parts = payload.split(":", 3);
                assertTrue(parts[2].chars().allMatch(c -> c == 'y'), "corrupted frame");
                keys.add(parts[0] + ":" + parts[1]);
            }
            assertEquals(writers * framesEach, keys.size());
        }
    }

}
