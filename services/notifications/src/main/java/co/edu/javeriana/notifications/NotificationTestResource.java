package co.edu.javeriana.notifications;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/test-notification")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class NotificationTestResource {
    @Inject
    private DisasterNotificationService notificationService;

    @POST
    public Response sendTestNotification(String eventJson) {
        if (!Boolean.parseBoolean(System.getenv("ENABLE_TEST_ENDPOINT"))) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        try {
            notificationService.notifyDisasterRegistered(eventJson);
            return Response.ok(Map.of("status", "sent")).build();
        } catch (IllegalArgumentException exception) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", exception.getMessage()))
                .build();
        } catch (Exception exception) {
            return Response.status(Response.Status.BAD_GATEWAY)
                .entity(Map.of("error", "No se pudo enviar la notificación"))
                .build();
        }
    }
}