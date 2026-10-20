/*
 * This file is part of a Bit libraries package.
 * Licensed under the GNU Lesser General Public License v3.0.
 *
 * Copyright (c) 2023-2026 ImBit
 */

package xyz.orbitary.perigee;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import xyz.orbitary.perigee.activity.Activity;
import xyz.orbitary.perigee.activity.ActivityType;
import xyz.orbitary.perigee.user.DiscordUser;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Talks to a real, running Discord desktop client, so it is skipped unless an application ID is provided:
 * <pre>PERIGEE_APP_ID=123456789012345678 ./gradlew test --tests '*LiveDiscordTest*' -i</pre>
 * It sets presence on the signed-in user's profile for a few seconds, so use a test account.
 */
@EnabledIfEnvironmentVariable(named = "PERIGEE_APP_ID", matches = "\\d+")
class LiveDiscordTest {
    private static PerigeeClient client() {
        return PerigeeClient.builder(Objects.requireNonNull(System.getenv("PERIGEE_APP_ID"))).build();
    }

    @Test
    void connectsSetsAndClearsPresence() throws Exception {
        try (PerigeeClient client = client()) {
            DiscordUser user = client.connect().get(10, TimeUnit.SECONDS);
            System.out.println("[live] connected as " + user);

            client.setActivity(a -> a
              .details("Perigee live test")
              .state("Check the elapsed timer")
              .startedAt(Instant.now().minusSeconds(90))
              .button("Repository", "https://github.com/Orbitary/Perigee")).get(10, TimeUnit.SECONDS);
            System.out.println("[live] presence set; the timer on your profile should read about 1:30 if timestamps are seconds");
            Thread.sleep(4_000);

            client.clearActivity().get(10, TimeUnit.SECONDS);
            System.out.println("[live] presence cleared");
            assertTrue(client.isConnected());
        }
    }

    @Test
    void reportsHowDiscordTreatsEachActivityType() throws Exception {
        try (PerigeeClient client = client()) {
            client.connect().get(10, TimeUnit.SECONDS);

            for (ActivityType type : ActivityType.values()) {
                if (type == ActivityType.CUSTOM) continue;
                Activity activity = Activity.of(a -> {
                    a.type(type).details("type " + type);
                    if (type == ActivityType.STREAMING) a.url("https://twitch.tv/perigee");
                });
                String outcome;
                try {
                    client.setActivity(activity).get(10, TimeUnit.SECONDS);
                    outcome = "accepted";
                } catch (ExecutionException e) {
                    outcome = "rejected: " + e.getCause();
                }
                System.out.println("[live] " + type + " (documented rpcSupported=" + type.rpcSupported() + ") -> " + outcome);
                Thread.sleep(1_000);
            }
            client.clearActivity().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void closesPromptly() throws Exception {
        PerigeeClient client = client();
        client.connect().get(10, TimeUnit.SECONDS);
        client.setActivity(a -> a.details("closing")).get(10, TimeUnit.SECONDS);

        assertTimeoutPreemptively(Duration.ofSeconds(5), client::close, "close() must not hang on the pipe");
        assertFalse(client.isConnected());
    }

}
