/*
 * This file is part of a Bit libraries package.
 * Licensed under the GNU Lesser General Public License v3.0.
 *
 * Copyright (c) 2023-2026 ImBit
 */

package xyz.orbitary.perigee.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;


class EndpointResolverTest {
    @Test
    void windowsUsesNamedPipesAndIgnoresTheEnvironment() {
        List<String> endpoints = EndpointResolver.resolve(true, Map.of(), List.of());

        assertEquals(10, endpoints.size());
        assertEquals("\\\\?\\pipe\\discord-ipc-0", endpoints.getFirst());
        assertEquals("\\\\?\\pipe\\discord-ipc-9", endpoints.getLast());
        assertEquals(endpoints, EndpointResolver.resolve(true, Map.of("XDG_RUNTIME_DIR", "/run/user/1", "TMPDIR", "/var/tmp"), List.of("/custom")));
    }

    @Test
    void unixFollowsDocumentedOrderWithSandboxesAndFallback() {
        Map<String, String> env = Map.of("XDG_RUNTIME_DIR", "/run/user/1", "TMPDIR", "/var/tmp/", "TEMP", "/run/user/1");

        List<String> endpoints = EndpointResolver.resolve(false, env, List.of("/custom"));

        List<String> firstOfEach = List.of(0, 10, 20, 30, 40, 50, 60, 70, 80, 90).stream().map(endpoints::get).toList();
        assertEquals(
          List.of(
            "/custom/discord-ipc-0",
            "/run/user/1/discord-ipc-0",
            "/run/user/1/app/com.discordapp.Discord/discord-ipc-0",
            "/run/user/1/snap.discord/discord-ipc-0",
            "/run/user/1/app/com.discordapp.DiscordPTB/discord-ipc-0",
            "/run/user/1/snap.discord-ptb/discord-ipc-0",
            "/run/user/1/app/com.discordapp.DiscordCanary/discord-ipc-0",
            "/run/user/1/snap.discord-canary/discord-ipc-0",
            "/var/tmp/discord-ipc-0",
            "/tmp/discord-ipc-0"
          ), firstOfEach
        );
        assertEquals(100, endpoints.size());
        assertEquals("/tmp/discord-ipc-9", endpoints.getLast());
    }

    @Test
    void eachDirectoryIsSearchedFromIndexZeroToNine() {
        List<String> endpoints = EndpointResolver.resolve(false, Map.of(), List.of("/custom"));

        for (int i = 0; i < 10; i++) {
            assertEquals("/custom/discord-ipc-" + i, endpoints.get(i));
        }
    }

    @Test
    void skipsUnsetAndBlankVariables() {
        List<String> endpoints = EndpointResolver.resolve(false, Map.of("TMPDIR", " "), List.of());

        assertEquals(10, endpoints.size());
        assertEquals("/tmp/discord-ipc-0", endpoints.getFirst());
    }

    @Test
    void neverListsAnEndpointTwice() {
        Map<String, String> env = Map.of("XDG_RUNTIME_DIR", "/run/user/1", "TMPDIR", "/run/user/1/", "TMP", "/tmp", "TEMP", "/tmp/");

        List<String> endpoints = EndpointResolver.resolve(false, env, List.of("/tmp", "/run/user/1"));

        assertEquals(endpoints.size(), new HashSet<>(endpoints).size());
    }

    @Test
    void trailingSlashesAreEquivalent() {
        assertEquals(
          EndpointResolver.resolve(false, Map.of("XDG_RUNTIME_DIR", "/run/user/1"), List.of()),
          EndpointResolver.resolve(false, Map.of("XDG_RUNTIME_DIR", "/run/user/1/"), List.of())
        );
    }

    @Test
    void sandboxDirectoriesOnlyDeriveFromTheRuntimeDirectory() {
        List<String> fromTmp = EndpointResolver.resolve(false, Map.of("TMPDIR", "/var/tmp"), List.of());
        List<String> fromRuntime = EndpointResolver.resolve(false, Map.of("XDG_RUNTIME_DIR", "/run/user/1"), List.of());

        assertTrue(fromTmp.stream().noneMatch(path -> path.contains("snap.discord") || path.contains("com.discordapp.Discord")));
        assertTrue(fromRuntime.contains("/run/user/1/snap.discord/discord-ipc-0"));
        assertTrue(fromRuntime.contains("/run/user/1/app/com.discordapp.Discord/discord-ipc-0"));
    }

    @Test
    void ptbAndCanaryInstallsAreSearchedInsideTheirSandboxes() {
        List<String> endpoints = EndpointResolver.resolve(false, Map.of("XDG_RUNTIME_DIR", "/run/user/1"), List.of());

        for (String sandbox : List.of("app/com.discordapp.DiscordPTB", "snap.discord-ptb", "app/com.discordapp.DiscordCanary", "snap.discord-canary")) {
            assertTrue(endpoints.contains("/run/user/1/" + sandbox + "/discord-ipc-0"), sandbox);
        }
    }

    @Test
    void flatpakClientsAreFoundInsideTheirXdgRunFolders(@TempDir Path runtime) throws IOException {
        Files.createDirectories(runtime.resolve(".flatpak/dev.vencord.Vesktop/xdg-run"));
        Files.createDirectories(runtime.resolve(".flatpak/123456"));

        List<String> endpoints = EndpointResolver.resolve(false, Map.of("XDG_RUNTIME_DIR", runtime.toString()), List.of());

        int vesktop = endpoints.indexOf(runtime + "/.flatpak/dev.vencord.Vesktop/xdg-run/discord-ipc-0");
        assertTrue(vesktop > endpoints.indexOf(runtime + "/snap.discord-canary/discord-ipc-0"), "after the official sandboxes");
        assertTrue(endpoints.stream().noneMatch(path -> path.contains(".flatpak/123456")), "instance folders without xdg-run are skipped");
    }

    static Stream<Arguments> macOsEnvironments() {
        return Stream.of(
          Arguments.of("TMPDIR only", Map.of("TMPDIR", "/var/folders/ab/cd/T/"), "/var/folders/ab/cd/T/discord-ipc-0", 20),
          Arguments.of("TMPDIR without trailing slash", Map.of("TMPDIR", "/var/folders/ab/cd/T"), "/var/folders/ab/cd/T/discord-ipc-0", 20),
          Arguments.of("nothing set", Map.<String, String>of(), "/tmp/discord-ipc-0", 10)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("macOsEnvironments")
    void macOsLooksInTmpdirThenTmpWithoutRuntimeDirectory(String name, Map<String, String> env, String first, int expectedSize) {
        List<String> endpoints = EndpointResolver.resolve(false, env, List.of());

        assertEquals(first, endpoints.getFirst());
        assertEquals("/tmp/discord-ipc-9", endpoints.getLast());
        assertEquals(expectedSize, endpoints.size());
        assertTrue(endpoints.stream().noneMatch(path -> path.contains("snap.") || path.contains("com.discordapp")), "no sandbox directories without XDG_RUNTIME_DIR");
    }

    @Test
    void thePreferredIndexIsTriedFirstInEveryDirectory() {
        List<String> endpoints = EndpointResolver.resolve(false, Map.of("TMPDIR", "/var/tmp"), List.of(), 3);

        assertEquals(List.of("/var/tmp/discord-ipc-3", "/var/tmp/discord-ipc-0", "/var/tmp/discord-ipc-1", "/var/tmp/discord-ipc-2", "/var/tmp/discord-ipc-4"), endpoints.subList(0, 5));
        assertEquals("/tmp/discord-ipc-3", endpoints.get(10));
        assertEquals(20, endpoints.size());
        assertEquals(endpoints.size(), new HashSet<>(endpoints).size());
        assertEquals("\\\\?\\pipe\\discord-ipc-9", EndpointResolver.resolve(true, Map.of(), List.of(), 9).getFirst());
    }

    @Test
    void thePreferredIndexMustBeAValidIpcIndex() {
        assertThrows(IllegalArgumentException.class, () -> EndpointResolver.resolve(false, Map.of(), List.of(), -1));
        assertThrows(IllegalArgumentException.class, () -> EndpointResolver.resolve(false, Map.of(), List.of(), 10));
    }

    @Test
    void theDefaultResolutionMatchesTheCurrentPlatform() {
        List<String> endpoints = EndpointResolver.resolve();

        assertFalse(endpoints.isEmpty());
        if (EndpointResolver.isWindows()) {
            assertTrue(endpoints.stream().allMatch(path -> path.startsWith("\\\\?\\pipe\\discord-ipc-")));
        } else {
            assertTrue(endpoints.stream().allMatch(path -> path.startsWith("/")));
            assertTrue(endpoints.contains("/tmp/discord-ipc-0"));
        }
    }

}
