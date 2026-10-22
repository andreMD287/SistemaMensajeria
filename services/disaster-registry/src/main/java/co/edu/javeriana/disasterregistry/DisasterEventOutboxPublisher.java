package co.edu.javeriana.disasterregistry;

import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.Topic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
@Startup
public class DisasterEventOutboxPublisher {
    private static final Logger LOG =
        Logger.getLogger(DisasterEventOutboxPublisher.class.getName());

    private static final int BATCH_SIZE = 50;

    @Inject
    @jakarta.jms.JMSConnectionFactory("java:/jms/RemoteCF")
    private JMSContext jmsContext;

    @Schedule(second = "*/2", minute = "*", hour = "*", persistent = false)
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void publishNextPendingEvent() {
        String url = env("DB_URL", "jdbc:postgresql://postgres:5432/disasters");
        String user = env("DB_USER", "disaster");
        String password = env("DB_PASSWORD", "disaster");

        String selectSql = """
            SELECT event_id, event_type, event_payload::text AS event_payload
            FROM event_outbox
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """;

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);

            try {
                List<PendingEvent> events = new ArrayList<>();

                try (PreparedStatement select = connection.prepareStatement(selectSql);
                     ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        events.add(new PendingEvent(
                            result.getObject("event_id", UUID.class),
                            result.getString("event_type"),
                            result.getString("event_payload")
                        ));
                    }
                }

                if (events.isEmpty()) {
                    connection.commit();
                    return;
                }

                Topic topic = jmsContext.createTopic("disaster.events");

                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE event_outbox
                    SET published_at = CURRENT_TIMESTAMP
                    WHERE event_id = ? AND published_at IS NULL
                    """)) {
                    for (PendingEvent event : events) {
                        JMSProducer producer = jmsContext.createProducer()
                            .setDeliveryMode(DeliveryMode.PERSISTENT)
                            .setProperty("eventType", event.eventType());

                        producer.send(topic, event.payload());

                        update.setObject(1, event.eventId());
                        if (update.executeUpdate() != 1) {
                            throw new IllegalStateException(
                                "No se pudo marcar como publicado el evento "
                                    + event.eventId()
                            );
                        }
                    }
                }

                connection.commit();
                LOG.info("Eventos publicados en disaster.events: " + events.size());
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (Exception exception) {
            LOG.log(Level.WARNING,
                "No se pudo publicar el lote del outbox; se reintentara",
                exception);
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record PendingEvent(UUID eventId, String eventType, String payload) {
    }
}