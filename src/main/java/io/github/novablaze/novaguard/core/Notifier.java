package io.github.novablaze.novaguard.core;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.notify.SmtpClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocketFactory;

/** Sends important events to a Discord webhook and/or an email address, off the main thread. */
public final class Notifier {

    private static final int DISCORD_LIMIT = 1900;

    private final NovaGuardPlugin plugin;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "NovaGuard-notify");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Notifier(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void send(String subject, String body) {
        Settings settings = plugin.settings();
        String webhook = settings.discordWebhook;
        boolean email = settings.emailEnabled && !settings.emailTo.isBlank();
        if (webhook.isEmpty() && !email) {
            return;
        }
        worker.execute(() -> {
            if (!webhook.isEmpty()) {
                discord(webhook, "**" + subject + "**\n" + body);
            }
            if (email) {
                email(settings, subject, body);
            }
        });
    }

    /** Sends a test message right now; used by /ng notifytest. */
    public void test() {
        send("NovaGuard test message", "If you can read this, notifications work.");
    }

    private void discord(String webhook, String text) {
        String content = text.length() > DISCORD_LIMIT ? text.substring(0, DISCORD_LIMIT) + "..." : text;
        String json = "{\"content\":\"" + escape(content) + "\"}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                plugin.getLogger().warning("Discord webhook answered HTTP " + response.statusCode());
            }
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().warning("Could not send the Discord notification: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void email(Settings settings, String subject, String body) {
        try {
            String from = settings.emailFrom.isBlank() ? settings.smtpUsername : settings.emailFrom;
            new SmtpClient(SSLSocketFactory.getDefault()).send(settings.smtpHost, settings.smtpPort,
                settings.smtpUsername, settings.smtpPassword, from, settings.emailTo, subject, body);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not send the notification email (" + e.getMessage()
                + "). Many hosts block outgoing mail; a Discord webhook usually still works.");
        }
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (char ch : text.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> { }
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    public void shutdown() {
        worker.shutdown();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
