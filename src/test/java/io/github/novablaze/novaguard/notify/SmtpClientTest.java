package io.github.novablaze.novaguard.notify;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import org.junit.jupiter.api.Test;

class SmtpClientTest {

    /** A tiny fake mail server that accepts one message and stores everything it was sent. */
    private static Thread fakeServer(ServerSocket server, AtomicReference<String> received, boolean rejectAuth) {
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                StringBuilder log = new StringBuilder();
                out.print("220 fake ESMTP\r\n");
                out.flush();
                String line;
                boolean data = false;
                int authStep = 0;
                while ((line = in.readLine()) != null) {
                    log.append(line).append('\n');
                    if (data) {
                        if (line.equals(".")) {
                            data = false;
                            out.print("250 queued\r\n");
                            out.flush();
                        }
                        continue;
                    }
                    if (authStep == 1) {
                        authStep = 2;
                        out.print("334 UGFzc3dvcmQ6\r\n");
                    } else if (authStep == 2) {
                        authStep = 0;
                        out.print(rejectAuth ? "535 bad credentials\r\n" : "235 ok\r\n");
                    } else if (line.startsWith("EHLO")) {
                        out.print("250-fake\r\n250 AUTH LOGIN\r\n");
                    } else if (line.equals("AUTH LOGIN")) {
                        authStep = 1;
                        out.print("334 VXNlcm5hbWU6\r\n");
                    } else if (line.startsWith("DATA")) {
                        data = true;
                        out.print("354 go\r\n");
                    } else if (line.equals("QUIT")) {
                        out.print("221 bye\r\n");
                        out.flush();
                        break;
                    } else {
                        out.print("250 ok\r\n");
                    }
                    out.flush();
                }
                received.set(log.toString());
            } catch (IOException ignored) {
                // Test server closed.
            }
        });
        thread.start();
        return thread;
    }

    @Test
    void sendsAFullMessage() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread thread = fakeServer(server, received, false);
            new SmtpClient(SocketFactory.getDefault()).send("localhost", server.getLocalPort(), "bot@example.com",
                "secret", "bot@example.com", "staff@example.com", "Cheater queued", "Line one\n.hidden dot line");
            thread.join(5000);
        }
        String log = received.get();
        assertTrue(log.contains("MAIL FROM:<bot@example.com>"), log);
        assertTrue(log.contains("RCPT TO:<staff@example.com>"), log);
        assertTrue(log.contains("Subject: Cheater queued"), log);
        assertTrue(log.contains("..hidden dot line"), "lines starting with a dot must be doubled: " + log);
    }

    @Test
    void wrongPasswordThrows() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            fakeServer(server, received, true);
            assertThrows(IOException.class, () -> new SmtpClient(SocketFactory.getDefault()).send("localhost",
                server.getLocalPort(), "bot", "wrong", "bot@example.com", "staff@example.com", "s", "b"));
        }
    }
}
