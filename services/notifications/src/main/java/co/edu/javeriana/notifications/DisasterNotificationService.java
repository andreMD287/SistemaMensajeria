package co.edu.javeriana.notifications;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;
import java.util.logging.Logger;

@ApplicationScoped
public class DisasterNotificationService {
    private static final Logger LOG =
        Logger.getLogger(DisasterNotificationService.class.getName());

    /**
     * Envía el correo del evento a MailHog y registra el SMS simulado.
     * El consumidor de mensajería podrá invocar este método.
     */
    public void notifyDisasterRegistered(String json) throws Exception {
        Event event;
        try (Jsonb jsonb = JsonbBuilder.create()) {
            event = jsonb.fromJson(json, Event.class);
        }

        validate(event);
        sendEmail(event);
        simulateSms(event);
    }

    private void sendEmail(Event event) throws Exception {
        String host = env("SMTP_HOST", "mailhog");
        String port = env("SMTP_PORT", "1025");
        String from = env("MAIL_FROM", "alertas@desastres.local");
        String recipients = env("NOTIFICATION_EMAIL_TO", "pruebas@desastres.local");

        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", port);
        properties.put("mail.smtp.auth", "false");
        properties.put("mail.smtp.starttls.enable", "false");
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "5000");
        properties.put("mail.smtp.writetimeout", "5000");

        Session session = Session.getInstance(properties);
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(
            Message.RecipientType.TO,
            InternetAddress.parse(recipients)
        );
        message.setSubject(
            "[" + event.disaster.severity + "] Desastre registrado: "
                + event.disaster.disasterType,
            "UTF-8"
        );
        message.setText(emailBody(event), "UTF-8");

        Transport.send(message);
        LOG.info("Correo enviado a MailHog para el evento " + event.eventId);
    }

    private void simulateSms(Event event) {
        String phone = env("SMS_PHONE", "+573000000000");
        LOG.info(
            "SMS SIMULADO para " + phone
                + ": " + event.disaster.disasterType
                + " con severidad " + event.disaster.severity
                + ". " + event.disaster.description
        );
    }

    private static String emailBody(Event event) {
        Disaster disaster = event.disaster;
        return """
            Se registró un desastre.

            Tipo: %s
            Severidad: %s
            Fuente: %s
            Fecha del evento: %s
            Descripción: %s
            Ubicación: %s, %s
            ID del desastre: %s
            ID del reporte: %s
            """.formatted(
                disaster.disasterType,
                disaster.severity,
                disaster.sourceSystem,
                disaster.occurredAt,
                disaster.description,
                disaster.location.latitude,
                disaster.location.longitude,
                disaster.disasterId,
                disaster.reportId
            );
    }

    private static void validate(Event event) {
        if (event == null
                || !"1.0".equals(event.schemaVersion)
                || !"DisasterRegistered".equals(event.eventType)
                || event.eventId == null
                || event.disaster == null
                || event.disaster.disasterId == null
                || event.disaster.reportId == null
                || event.disaster.disasterType == null
                || event.disaster.severity == null
                || event.disaster.sourceSystem == null
                || event.disaster.description == null
                || event.disaster.occurredAt == null
                || event.disaster.location == null
                || event.disaster.location.latitude == null
                || event.disaster.location.longitude == null) {
            throw new IllegalArgumentException("Evento DisasterRegistered inválido");
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static class Event {
        public String schemaVersion;
        public String eventId;
        public String eventType;
        public String occurredAt;
        public Disaster disaster;
    }

    public static class Disaster {
        public String disasterId;
        public String reportId;
        public String sourceSystem;
        public String disasterType;
        public String severity;
        public String description;
        public String occurredAt;
        public Location location;
    }

    public static class Location {
        public Double latitude;
        public Double longitude;
    }
}