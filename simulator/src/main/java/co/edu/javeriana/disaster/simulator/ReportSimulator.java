package co.edu.javeriana.disaster.simulator;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Simula sistemas externos que reportan desastres publicando directamente en RabbitMQ.
 *
 * Uso: --count N (default 1) | --duplicate (reenvía cada reporte dos veces) | --interval-ms N
 */
public class ReportSimulator {

    private static final String EXCHANGE = "disaster.reports";
    private static final String ROUTING_KEY = "report.received";

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

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(env("RABBITMQ_HOST", "localhost"));
        factory.setPort(Integer.parseInt(env("RABBITMQ_PORT", "5672")));
        factory.setUsername(env("RABBITMQ_USER", "disaster"));
        factory.setPassword(env("RABBITMQ_PASS", "disaster"));

        Random random = new Random();
        try (Connection connection = factory.newConnection("report-simulator");
             Channel channel = connection.createChannel()) {
            channel.confirmSelect();

            for (int i = 0; i < count; i++) {
                String reportId = UUID.randomUUID().toString();
                String json = buildReport(reportId, random);
                publish(channel, reportId, json);
                if (duplicate) {
                    publish(channel, reportId, json);
                }
                Thread.sleep(intervalMs);
            }
            channel.waitForConfirmsOrDie(5_000);
        }
    }

    private static void publish(Channel channel, String reportId, String json) throws Exception {
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .contentEncoding("UTF-8")
                .deliveryMode(MessageProperties.PERSISTENT_BASIC.getDeliveryMode())
                .messageId(reportId)
                .build();
        channel.basicPublish(EXCHANGE, ROUTING_KEY, props, json.getBytes(StandardCharsets.UTF_8));
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
