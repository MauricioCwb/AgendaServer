package br.com.mauricio.agendaserver;

import org.springframework.stereotype.Service;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Service
final class ExternalInviteMailer {
    private final ProspectingSettingsService settings;

    ExternalInviteMailer(ProspectingSettingsService settings) {
        this.settings = settings;
    }

    SendResult send(String destination, String specialty, String region, double distanceKm,
                    String dateLabel, String inviteLink, String optOutLink) {
        ProspectingSettingsService.Snapshot configuration = settings.snapshot();
        if (!configuration.realSendingAllowed()) {
            return new SendResult(false, "Envio real bloqueado por configuração ou PRODUCAO=false.");
        }
        try {
            String safeDestination = headerValue(destination, 254);
            String safeSpecialty = headerValue(specialty, 120);
            String safeRegion = headerValue(region, 120);
            sendSmtp(configuration, safeDestination,
                    "Solicitação de " + safeSpecialty + " na sua região",
                    body(safeDestination, safeSpecialty, safeRegion, distanceKm, dateLabel, inviteLink, optOutLink));
            return new SendResult(true, "");
        } catch (Exception exception) {
            String message = exception.getMessage();
            return new SendResult(false, message == null ? exception.getClass().getSimpleName()
                    : message.substring(0, Math.min(message.length(), 300)));
        }
    }

    private static void sendSmtp(ProspectingSettingsService.Snapshot configuration,
                                 String destination, String subject, String body) throws Exception {
        Socket socket = configuration.smtpPort() == 465
                ? SSLSocketFactory.getDefault().createSocket(configuration.smtpHost(), configuration.smtpPort())
                : new Socket(configuration.smtpHost(), configuration.smtpPort());
        socket.setSoTimeout((int) Duration.ofSeconds(30).toMillis());
        SmtpChannel channel = new SmtpChannel(socket);
        channel.expect(220);
        String ehlo = channel.command("EHLO agendafaz.com.br", 250);
        if (configuration.smtpPort() != 465 && ehlo.toUpperCase().contains("STARTTLS")) {
            channel.command("STARTTLS", 220);
            socket = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, configuration.smtpHost(), configuration.smtpPort(), true);
            socket.setSoTimeout((int) Duration.ofSeconds(30).toMillis());
            channel = new SmtpChannel(socket);
            channel.command("EHLO agendafaz.com.br", 250);
        }
        if (!configuration.smtpUsername().isBlank()) {
            channel.command("AUTH LOGIN", 334);
            channel.command(Base64.getEncoder().encodeToString(configuration.smtpUsername().getBytes(StandardCharsets.UTF_8)), 334);
            channel.command(Base64.getEncoder().encodeToString(configuration.smtpPassword().getBytes(StandardCharsets.UTF_8)), 235);
        }
        channel.command("MAIL FROM:<" + configuration.smtpFrom() + ">", 250);
        channel.command("RCPT TO:<" + destination + ">", 250, 251);
        channel.command("DATA", 354);
        String encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=";
        String message = "From: AgendaFz <" + configuration.smtpFrom() + ">\r\n"
                + "To: <" + destination + ">\r\n"
                + "Subject: " + encodedSubject + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n"
                + "Auto-Submitted: auto-generated\r\n"
                + "List-Unsubscribe: <" + extractOptOutUrl(body) + ">\r\n"
                + "\r\n" + dotStuff(body) + "\r\n.";
        channel.command(message, 250);
        try { channel.command("QUIT", 221); } finally { socket.close(); }
    }

    static String body(String email, String specialty, String region, double distanceKm,
                       String dateLabel, String inviteLink, String optOutLink) {
        String dateText = dateLabel == null || dateLabel.isBlank() ? "" : " Data aproximada: " + dateLabel + ".";
        return "Olá,\n\n"
                + "Existe uma solicitação de serviço de " + specialty + " na região de " + region
                + ", a aproximadamente " + String.format(java.util.Locale.US, "%.1f", distanceKm).replace('.', ',') + " km do seu estabelecimento."
                + dateText + "\n\n"
                + "Encontramos este contato público no cadastro público do CNPJ. Caso tenha interesse, você pode criar gratuitamente seu perfil no AgendaFz e conhecer a oportunidade. O cadastro e a participação são opcionais; não há promessa de contratação ou renda.\n\n"
                + "Para que o sistema associe corretamente esta demanda, mantenha o e-mail " + email + " no cadastro.\n\n"
                + "Conhecer a oportunidade: " + inviteLink + "\n\n"
                + "Se não quiser receber novos convites, confirme o descadastro aqui: " + optOutLink + "\n\n"
                + "AgendaFz — Você agenda. A gente faz acontecer.\n"
                + "agendafaz.com.br";
    }

    private static String headerValue(String value, int max) {
        String clean = value == null ? "" : value.replace("\r", " ").replace("\n", " ").trim();
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static String extractOptOutUrl(String body) {
        int index = body.indexOf("descadastro aqui: ");
        if (index < 0) return "mailto:nao-responder@localhost";
        String value = body.substring(index + "descadastro aqui: ".length()).split("\\s", 2)[0].trim();
        return value.isBlank() ? "mailto:nao-responder@localhost" : value;
    }

    private static String dotStuff(String value) {
        return value.replace("\r\n", "\n").replace("\r", "\n")
                .lines().map(line -> line.startsWith(".") ? "." + line : line)
                .reduce((left, right) -> left + "\r\n" + right).orElse("");
    }

    private static final class SmtpChannel {
        private final BufferedReader reader;
        private final BufferedWriter writer;

        SmtpChannel(Socket socket) throws Exception {
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        String command(String command, int... acceptedCodes) throws Exception {
            writer.write(command);
            writer.write("\r\n");
            writer.flush();
            return expect(acceptedCodes);
        }

        String expect(int... acceptedCodes) throws Exception {
            StringBuilder response = new StringBuilder();
            int code = -1;
            while (true) {
                String line = reader.readLine();
                if (line == null) throw new IllegalStateException("Conexão SMTP encerrada inesperadamente.");
                if (!response.isEmpty()) response.append('\n');
                response.append(line);
                if (line.length() >= 3 && Character.isDigit(line.charAt(0))) {
                    code = Integer.parseInt(line.substring(0, 3));
                    if (line.length() < 4 || line.charAt(3) != '-') break;
                }
            }
            for (int accepted : acceptedCodes) if (code == accepted) return response.toString();
            throw new IllegalStateException("SMTP recusou a operação com código " + code + ".");
        }
    }

    record SendResult(boolean sent, String error) {}
}
