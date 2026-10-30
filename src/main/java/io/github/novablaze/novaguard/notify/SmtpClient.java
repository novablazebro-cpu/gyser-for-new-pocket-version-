package io.github.novablaze.novaguard.notify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import javax.net.SocketFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Minimal email sender (SMTP with AUTH LOGIN). In the plugin it runs over SSL on port 465
 * (e.g. Gmail with an app password). No external library needed.
 */
public final class SmtpClient {

    private static final int TIMEOUT_MS = 15_000;

    private final SocketFactory sockets;

    public SmtpClient(SocketFactory sockets) {
        this.sockets = sockets;
    }

    public void send(String host, int port, String username, String password, String from, String to,
                     String subject, String body) throws IOException {
        try (Socket socket = sockets.createSocket()) {
            if (socket instanceof SSLSocket ssl) {
                // Check that the certificate really belongs to the mail server: without this, anyone on the
                // network path could pretend to be smtp.gmail.com and read the app password.
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName(host)));
                ssl.setSSLParameters(parameters);
            }
            // Connect with a timeout: hosts that silently drop mail ports would otherwise hang for minutes.
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            if (socket instanceof SSLSocket ssl) {
                ssl.startHandshake();
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            expect(in, 220);
            command(out, "EHLO novaguard");
            expect(in, 250);
            command(out, "AUTH LOGIN");
            expect(in, 334);
            command(out, base64(username));
            expect(in, 334);
            command(out, base64(password));
            expect(in, 235);
            command(out, "MAIL FROM:<" + from + ">");
            expect(in, 250);
            command(out, "RCPT TO:<" + to + ">");
            expect(in, 250);
            command(out, "DATA");
            expect(in, 354);
            StringBuilder message = new StringBuilder();
            message.append("From: NovaGuard <").append(from).append(">\r\n");
            message.append("To: <").append(to).append(">\r\n");
            message.append("Subject: ").append(clean(subject)).append("\r\n");
            message.append("Date: ").append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\r\n");
            message.append("MIME-Version: 1.0\r\n");
            message.append("Content-Type: text/plain; charset=UTF-8\r\n");
            message.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
            for (String line : body.replace("\r", "").split("\n", -1)) {
                // A line with only "." would end the message early: SMTP requires doubling a leading dot.
                message.append(line.startsWith(".") ? "." + line : line).append("\r\n");
            }
            message.append(".\r\n");
            out.write(message.toString());
            out.flush();
            expect(in, 250);
            command(out, "QUIT");
        }
    }

    private static void command(Writer out, String line) throws IOException {
        out.write(line + "\r\n");
        out.flush();
    }

    /** Reads a (possibly multi-line) reply and checks its status code. */
    private static void expect(BufferedReader in, int code) throws IOException {
        String line;
        do {
            line = in.readLine();
            if (line == null) {
                throw new IOException("Mail server closed the connection (expected " + code + ")");
            }
        } while (line.length() > 3 && line.charAt(3) == '-');
        if (!line.startsWith(String.valueOf(code))) {
            throw new IOException("Mail server said: " + line + " (expected " + code + ")");
        }
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String clean(String header) {
        return header.replace("\r", " ").replace("\n", " ");
    }
}
