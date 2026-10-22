package co.edu.javeriana.notifications;

import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.MessageDriven;
import jakarta.inject.Inject;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;
import org.jboss.ejb3.annotation.ResourceAdapter;

import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

@ResourceAdapter("artemis-remote-cf")
@MessageDriven(
    name = "DisasterRegisteredNotificationMDB",
    activationConfig = {
        @ActivationConfigProperty(propertyName = "useJNDI", propertyValue = "false"),
        @ActivationConfigProperty(propertyName = "destination", propertyValue = "disaster.events::notifications.disaster-registered"),
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "jakarta.jms.Queue")
    }
)
public class DisasterRegisteredNotificationMDB implements MessageListener {

    private static final Logger LOGGER =
        Logger.getLogger(DisasterRegisteredNotificationMDB.class.getName());

    @Inject
    private DisasterNotificationService notificationService;

    @Override
    public void onMessage(Message message) {
        try {
            String json = readBody(message);
            notificationService.notifyDisasterRegistered(json);
            LOGGER.info("Notificación procesada. JMSMessageID="
                + message.getJMSMessageID());
        } catch (Exception e) {
            LOGGER.severe("Falló el envío de la notificación: " + e.getMessage());
            throw new IllegalStateException("No se pudo procesar el evento", e);
        }
    }

    private String readBody(Message message) throws JMSException {
        if (message instanceof TextMessage textMessage) {
            return textMessage.getText();
        }

        if (message instanceof BytesMessage bytesMessage) {
            long length = bytesMessage.getBodyLength();
            if (length > Integer.MAX_VALUE) {
                throw new JMSException("El mensaje excede el tamaño soportado");
            }

            byte[] data = new byte[(int) length];
            int offset = 0;

            while (offset < data.length) {
                int count = bytesMessage.readBytes(data, data.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }

            if (offset != data.length) {
                throw new JMSException("El cuerpo del mensaje llegó incompleto");
            }

            return new String(data, StandardCharsets.UTF_8);
        }

        throw new JMSException(
            "Tipo de mensaje no soportado: " + message.getClass().getName()
        );
    }
}