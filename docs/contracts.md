# Contratos de mensajería

## Topología RabbitMQ

| Exchange | Tipo | Routing key | Cola destino |
|---|---|---|---|
| `disaster.reports` | direct | `report.received` | `disaster.registration.in` |
| `disaster.events` | topic | `disaster.registered.v1` | `notifications.disaster-registered` |
| `disaster.dlx` | direct | `disaster.registration.dlq` | `disaster.registration.dlq` |
| `disaster.dlx` | direct | `notifications.dlq` | `notifications.dlq` |

Las colas de entrada tienen `x-dead-letter-exchange=disaster.dlx`: un mensaje rechazado (`basicNack` sin requeue) termina en su DLQ.
Otros sistemas suscritos crean su propia cola y la enlazan a `disaster.events` con `disaster.registered.v1`.
La topología se carga desde `infra/rabbitmq/definitions.json` al iniciar el broker.

## Reporte entrante (`disaster.reports`)

Propiedades AMQP: `content_type=application/json`, `delivery_mode=2`, `message_id=<reportId>`.

```json
{
  "schemaVersion": "1.0",
  "reportId": "uuid",
  "sourceSystem": "IDEAM",
  "disasterType": "EARTHQUAKE | FLOOD | LANDSLIDE | FIRE",
  "reportedAt": "2026-10-20T16:00:00Z",
  "occurredAt": "2026-10-20T15:45:00Z",
  "severity": "LOW | MEDIUM | HIGH | CRITICAL",
  "description": "texto libre",
  "location": { "latitude": 4.7110, "longitude": -74.0721 }
}
```

Idempotencia: `reportId` es único; si se reentrega, Registro no duplica el desastre.

## Evento `DisasterRegistered` (`disaster.events`)

```json
{
  "schemaVersion": "1.0",
  "eventId": "uuid",
  "eventType": "DisasterRegistered",
  "occurredAt": "2026-10-20T16:00:01Z",
  "disaster": {
    "disasterId": "uuid",
    "reportId": "uuid",
    "sourceSystem": "IDEAM",
    "disasterType": "EARTHQUAKE",
    "severity": "HIGH",
    "description": "texto libre",
    "occurredAt": "2026-10-20T15:45:00Z",
    "location": { "latitude": 4.7110, "longitude": -74.0721 }
  }
}
```

> Confirmado con Rol 2: `location` es objeto lat/lon; `disasterType` ∈ EARTHQUAKE/FLOOD/LANDSLIDE/FIRE; `severity` ∈ LOW/MEDIUM/HIGH/CRITICAL; los datos del evento van dentro de `disaster`.
