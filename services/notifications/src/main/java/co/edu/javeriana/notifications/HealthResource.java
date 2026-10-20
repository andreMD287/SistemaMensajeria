package co.edu.javeriana.notifications;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {
    @GET
    public Map<String, String> health() {
        return Map.of(
            "status", "UP",
            "service", "notifications"
        );
    }
}
