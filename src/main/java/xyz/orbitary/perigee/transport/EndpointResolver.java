/*
 * This file is part of a Bit libraries package.
 * Licensed under the GNU Lesser General Public License v3.0.
 *
 * Copyright (c) 2023-2026 ImBit
 */

package xyz.orbitary.perigee.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;


/**
 * Lists the IPC endpoints a Discord client may be listening on, most likely first.
 */
public final class EndpointResolver {
    public static final int MAX_INDEX = 9;
    private static final String WINDOWS_PIPE_PREFIX = "\\\\?\\pipe\\";
    private static final String[] UNIX_ENV_DIRS = {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"};
    private static final String[] SANDBOX_SUBDIRS = {
      "app/com.discordapp.Discord", "snap.discord",
      "app/com.discordapp.DiscordPTB", "snap.discord-ptb",
      "app/com.discordapp.DiscordCanary", "snap.discord-canary"
    };

    private EndpointResolver() {}

    /**
     * @param windows        whether to produce named pipes
     * @param env            environment variables
     * @param extraDirs      directories searched before the defaults
     * @param preferredIndex the {@code discord-ipc-N} index tried first in every directory, 0 to 9
     */
    public static List<String> resolve(boolean windows, Map<String, String> env, List<String> extraDirs, int preferredIndex) {
        if (preferredIndex < 0 || preferredIndex > MAX_INDEX) throw new IllegalArgumentException("preferredIndex must be between 0 and " + MAX_INDEX);
        Set<String> bases = new LinkedHashSet<>();
        if (windows) {
            bases.add(WINDOWS_PIPE_PREFIX);
        } else {
            for (String dir : extraDirs) {
                bases.add(withSeparator(dir));
            }
            for (String name : UNIX_ENV_DIRS) {
                String dir = env.get(name);
                if (dir == null || dir.isBlank()) continue;
                bases.add(withSeparator(dir));
                if (name.equals("XDG_RUNTIME_DIR")) {
                    for (String sub : SANDBOX_SUBDIRS) {
                        bases.add(withSeparator(dir) + sub + "/");
                    }
                    bases.addAll(flatpakRuntimeDirs(Path.of(dir, ".flatpak")));
                }
            }
            bases.add("/tmp/");
        }

        List<String> endpoints = new ArrayList<>(bases.size() * (MAX_INDEX + 1));
        for (String base : bases) {
            endpoints.add(base + "discord-ipc-" + preferredIndex);
            for (int i = 0; i <= MAX_INDEX; i++) {
                if (i != preferredIndex) endpoints.add(base + "discord-ipc-" + i);
            }
        }
        return endpoints;
    }

    public static List<String> resolve(boolean windows, Map<String, String> env, List<String> extraDirs) {
        return resolve(windows, env, extraDirs, 0);
    }

    public static List<String> resolve() {
        return resolve(isWindows(), System.getenv(), List.of());
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String withSeparator(String dir) {
        return dir.endsWith("/") ? dir : dir + "/";
    }

    /**
     * rather than littering SANDBOX_SUBDIRS with variations of
     * ".flatpak/dev.vencord.Vesktop/xdg-run"
     * for every client under the sun, find them all at once.
     */
    private static List<String> flatpakRuntimeDirs(Path flatpakDir) {
        try (Stream<Path> apps = Files.list(flatpakDir)) {
            return apps.map(app -> app.resolve("xdg-run"))
              .filter(Files::isDirectory)
              .map(path -> path + "/")
              .sorted()
              .toList();
        } catch (IOException | SecurityException e) {
            return List.of();
        }
    }

}
