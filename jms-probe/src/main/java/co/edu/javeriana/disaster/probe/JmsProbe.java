package co.edu.javeriana.disaster.probe;

import com.rabbitmq.jms.admin.RMQConnectionFactory;
import com.rabbitmq.jms.admin.RMQDestination;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import java.nio.charset.StandardCharsets;

/**
 * Prueba de viabilidad: consume por JMS (lo que hará un MDB) la cola AMQP existente
 * disaster.registration.in, alimentada por el simulador a través de disaster.reports.
 */
public class JmsProbe {

    public static void main(String[] args) throws Exception {
        int expected = args.length > 0 ? Integer.parseInt(args[0]) : 1;

        RMQConnectionFactory factory = new RMQConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("disaster");
        factory.setPassword("disaster");

        // Destino JMS mapeado a la topología AMQP existente (sin que JMS cree colas propias)
        RMQDestination destination = new RMQDestination(
                "disaster.registration.in", "disaster.reports", "report.received", "disaster.registration.in");

        try (Connection connection = factory.createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(destination);

            for (int i = 0; i < expected; i++) {
                Message message = consumer.receive(5_000);
                if (message == null) {
                    System.out.println("Sin mas mensajes tras " + i);
                    break;
                }
                String body = readBody(message);
                System.out.println("JMSMessageID=" + message.getJMSMessageID()
                        + " | contentType=" + message.getStringProperty("JMS_RMQ_content_type")
                        + " | redelivered=" + message.getJMSRedelivered());
                System.out.println(body.replaceAll("\s+", " "));
                message.acknowledge();
            }
        }
    }

    /** Mensajes publicados por AMQP llegan como BytesMessage; los publicados por JMS, como TextMessage. */
    static String readBody(Message message) throws Exception {
        if (message instanceof TextMessage text) {
            return text.getText();
        }
        BytesMessage bytes = (BytesMessage) message;
        byte[] data = new byte[(int) bytes.getBodyLength()];
        bytes.readBytes(data);
        return new String(data, StandardCharsets.UTF_8);
    }
}
