package co.edu.javeriana.disasterregistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

@ApplicationScoped
public class DisasterReportProcessor {
    private static final Logger LOG =
        Logger.getLogger(DisasterReportProcessor.class.getName());

    private static final Set<String> TYPES =
        Set.of("EARTHQUAKE", "FLOOD", "LANDSLIDE", "FIRE");
    private static final Set<String> SEVERITIES =
        Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    /**
     * Registra el reporte y su evento pendiente en una transacción.
     * Devuelve false si reportId ya había sido procesado.
     */
    public boolean register(String json) throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Report report = jsonb.fromJson(json, Report.class);
            validate(report);

            UUID reportId = UUID.fromString(report.reportId);
            UUID disasterId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();

            Event event = Event.from(report, disasterId, eventId, Instant.now().toString());
            String eventJson = jsonb.toJson(event);

            String url = env("DB_URL", "jdbc:postgresql://postgres:5432/disasters");
            String user = env("DB_USER", "disaster");
            String password = env("DB_PASSWORD", "disaster");

            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                connection.setAutoCommit(false);

                try {
                    String insertReport = """
                        INSERT INTO disaster_reports (report_id, report_payload)
                        VALUES (?, ?::jsonb)
                        ON CONFLICT (report_id) DO NOTHING
                        """;

                    int inserted;
                    try (PreparedStatement statement =
                             connection.prepareStatement(insertReport)) {
                        statement.setString(1, reportId.toString());
                        statement.setString(2, json);
                        inserted = statement.executeUpdate();
                    }

                    if (inserted == 0) {
                        connection.rollback();
                        LOG.info("Reporte duplicado ignorado: " + reportId);
                        return false;
                    }

                    String insertEvent = """
                        INSERT INTO event_outbox
                            (event_id, aggregate_id, event_type, event_payload)
                        VALUES (?, ?, ?, ?::jsonb)
                        """;

                    try (PreparedStatement statement =
                             connection.prepareStatement(insertEvent)) {
                        statement.setObject(1, eventId);
                        statement.setString(2, reportId.toString());
                        statement.setString(3, "DisasterRegistered");
                        statement.setString(4, eventJson);
                        statement.executeUpdate();
                    }

                    connection.commit();
                    LOG.info("Reporte registrado y evento guardado en outbox: " + reportId);
                    return true;
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                }
            }
        }
    }

    private static void validate(Report report) {
        if (report == null) {
            throw new IllegalArgumentException("El reporte está vacío");
        }
        required(report.schemaVersion, "schemaVersion");
        if (!"1.0".equals(report.schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion debe ser 1.0");
        }
        required(report.reportId, "reportId");
        UUID.fromString(report.reportId);
        required(report.sourceSystem, "sourceSystem");
        required(report.disasterType, "disasterType");
        if (!TYPES.contains(report.disasterType)) {
            throw new IllegalArgumentException("disasterType no reconocido");
        }
        required(report.severity, "severity");
        if (!SEVERITIES.contains(report.severity)) {
            throw new IllegalArgumentException("severity no reconocido");
        }
        required(report.description, "description");
        required(report.reportedAt, "reportedAt");
        required(report.occurredAt, "occurredAt");
        Instant.parse(report.reportedAt);
        Instant.parse(report.occurredAt);

        if (report.location == null
                || report.location.latitude == null
                || report.location.longitude == null) {
            throw new IllegalArgumentException("location requiere latitude y longitude");
        }
        if (report.location.latitude < -90 || report.location.latitude > 90
                || report.location.longitude < -180 || report.location.longitude > 180) {
            throw new IllegalArgumentException("Coordenadas fuera de rango");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static class Report {
        public String schemaVersion;
        public String reportId;
        public String sourceSystem;
        public String disasterType;
        public String reportedAt;
        public String occurredAt;
        public String severity;
        public String description;
        public Location location;
    }

    public static class Location {
        public Double latitude;
        public Double longitude;
    }

    public static class Event {
        public String schemaVersion;
        public UUID eventId;
        public String eventType;
        public String occurredAt;
        public Disaster disaster;

        static Event from(Report report, UUID disasterId, UUID eventId, String occurredAt) {
            Event event = new Event();
            event.schemaVersion = "1.0";
            event.eventId = eventId;
            event.eventType = "DisasterRegistered";
            event.occurredAt = occurredAt;
            event.disaster = Disaster.from(report, disasterId);
            return event;
        }
    }

    public static class Disaster {
        public UUID disasterId;
        public String reportId;
        public String sourceSystem;
        public String disasterType;
        public String severity;
        public String description;
        public String occurredAt;
        public Location location;

        static Disaster from(Report report, UUID disasterId) {
            Disaster disaster = new Disaster();
            disaster.disasterId = disasterId;
            disaster.reportId = report.reportId;
            disaster.sourceSystem = report.sourceSystem;
            disaster.disasterType = report.disasterType;
            disaster.severity = report.severity;
            disaster.description = report.description;
            disaster.occurredAt = report.occurredAt;
            disaster.location = report.location;
            return disaster;
        }
    }
}