package co.edu.javeriana.disaster.simulator;

import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Simula sistemas externos que reportan desastres publicando TextMessage JMS en Artemis.
 *
 * Uso: --count N (default 1) | --duplicate (reenvía cada reporte dos veces) | --interval-ms N
 */
public class ReportSimulator {

    private static final String QUEUE = "disaster.registration.in";

    private static final List<String> SOURCES = List.of("IDEAM", "SGC", "BOMBEROS-BOG", "CRUZ-ROJA");
    private static final List<String> TYPES = List.of("EARTHQUAKE", "FLOOD", "LANDSLIDE", "FIRE");
    private static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final List<double[]> LOCATIONS = List.of(
            new double[]{4.7110, -74.0721}, // Bogotá
            new double[]{6.2442, -75.5812}, // Medellín
            new double[]{3.4516, -76.5320}, // Cali
            new double[]{10.9685, -74.7813} // Barranquilla
    );

    public static void main(String[] args) throws Exception {
        int count = 1;
        boolean duplicate = false;
        long intervalMs = 500;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--count" -> count = Integer.parseInt(args[++i]);
                case "--duplicate" -> duplicate = true;
                case "--interval-ms" -> intervalMs = Long.parseLong(args[++i]);
                default -> throw new IllegalArgumentException("Argumento desconocido: " + args[i]);
            }
        }

        String url = "tcp://" + env("BROKER_HOST", "localhost") + ":" + env("BROKER_PORT", "61616");
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(
                url, env("BROKER_USER", "disaster"), env("BROKER_PASSWORD", "disaster"));

        Random random = new Random();
        try (Connection connection = factory.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(QUEUE);
            MessageProducer producer = session.createProducer(queue);
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);

            for (int i = 0; i < count; i++) {
                String reportId = UUID.randomUUID().toString();
                String json = buildReport(reportId, random);
                publish(session, producer, reportId, json);
                if (duplicate) {
                    publish(session, producer, reportId, json);
                }
                Thread.sleep(intervalMs);
            }
        }
    }

    private static void publish(Session session, MessageProducer producer, String reportId, String json) throws Exception {
        TextMessage message = session.createTextMessage(json);
        message.setStringProperty("reportId", reportId);
        message.setStringProperty("contentType", "application/json");
        producer.send(message);
        System.out.println("Publicado reportId=" + reportId);
    }

    private static String buildReport(String reportId, Random random) {
        String source = pick(SOURCES, random);
        String type = pick(TYPES, random);
        String severity = pick(SEVERITIES, random);
        double[] loc = LOCATIONS.get(random.nextInt(LOCATIONS.size()));
        Instant occurredAt = Instant.now().minusSeconds(60L * (1 + random.nextInt(30)));

        return """
                {
                  "schemaVersion": "1.0",
                  "reportId": "%s",
                  "sourceSystem": "%s",
                  "disasterType": "%s",
                  "reportedAt": "%s",
                  "occurredAt": "%s",
                  "severity": "%s",
                  "description": "Reporte simulado de %s",
                  "location": { "latitude": %s, "longitude": %s }
                }""".formatted(reportId, source, type, Instant.now(), occurredAt, severity,
                type.toLowerCase(), loc[0], loc[1]);
    }

    private static <T> T pick(List<T> list, Random random) {
        return list.get(random.nextInt(list.size()));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
