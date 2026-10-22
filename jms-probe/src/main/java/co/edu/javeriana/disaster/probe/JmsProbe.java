package co.edu.javeriana.disaster.probe;

import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Prueba de viabilidad del consumo asíncrono por JMS (equivalente a lo que hace un MDB).
 *
 * Modos:
 *   reports [n]  consume n mensajes de disaster.registration.in con un MessageListener y confirma
 *   events  [n]  publica n eventos en disaster.events y los consume desde la cola
 *                notifications.disaster-registered usando su nombre completo (FQQN)
 *   fail         consume un mensaje de disaster.registration.in y lo rechaza (rollback) hasta que
 *                Artemis lo mueve a disaster.registration.dlq
 */
public class JmsProbe {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "reports";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 1;

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(
                "tcp://" + env("BROKER_HOST", "localhost") + ":" + env("BROKER_PORT", "61616"),
                env("BROKER_USER", "disaster"), env("BROKER_PASSWORD", "disaster"));

        try (Connection connection = factory.createConnection()) {
            switch (mode) {
                case "reports" -> consume(connection, "disaster.registration.in", n, false);
                case "events" -> {
                    publishEvents(connection, n);
                    consume(connection, "disaster.events::notifications.disaster-registered", n, false);
                }
                case "fail" -> consume(connection, "disaster.registration.in", 6, true);
                default -> throw new IllegalArgumentException(mode);
            }
        }
    }

    private static void consume(Connection connection, String destination, int expected, boolean reject) throws Exception {
        Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
        Queue queue = session.createQueue(destination);
        MessageConsumer consumer = session.createConsumer(queue);
        CountDownLatch latch = new CountDownLatch(expected);

        consumer.setMessageListener(message -> {
            try {
                String body = ((TextMessage) message).getText().replaceAll("\s+", " ");
                System.out.println("[" + destination + "] reportId=" + message.getStringProperty("reportId")
                        + " deliveryCount=" + message.getIntProperty("JMSXDeliveryCount")
                        + (reject ? " -> RECHAZADO" : "") + "
  " + body);
                if (reject) {
                    session.rollback();
                } else {
                    session.commit();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            latch.countDown();
        });
        connection.start();
        if (!latch.await(15, TimeUnit.SECONDS)) {
            System.out.println("Timeout: llegaron " + (expected - latch.getCount()) + " de " + expected);
        }
    }

    private static void publishEvents(Connection connection, int n) throws Exception {
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageProducer producer = session.createProducer(session.createTopic("disaster.events"));
        for (int i = 0; i < n; i++) {
            TextMessage m = session.createTextMessage("{\"eventType\":\"DisasterRegistered\",\"n\":" + i + "}");
            m.setStringProperty("eventType", "DisasterRegistered");
            producer.send(m);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
