# Contratos de mensajería

Broker: **ActiveMQ Artemis** (JMS). Los servicios consumen con MDB; el simulador publica `TextMessage`.

## Conexión

| Dato | Valor |
|---|---|
| Host | `artemis` (Compose y Kubernetes) / `localhost` (desde la máquina) |
| Puerto | `61616` (core/JMS, AMQP y MQTT). Consola web: `8161` |
| Usuario / clave | `disaster` / `disaster` (solo desarrollo) |
| Variables de entorno | `BROKER_HOST`, `BROKER_PORT`, `BROKER_USER`, `BROKER_PASSWORD` |
| Pooled connection factory (WildFly) | `artemis-remote-cf` (JNDI `java:/jms/RemoteCF`) |

## Destinos

| Destino | Tipo | Quién publica | Quién consume |
|---|---|---|---|
| `disaster.registration.in` | Cola (anycast) | Sistemas emisores / simulador | MDB de Registro |
| `disaster.events` | Tópico (multicast) | Registro (evento `DisasterRegistered`) | Una cola por suscriptor |
| `notifications.disaster-registered` | Cola de suscripción de `disaster.events` | — | MDB de Notificaciones, leyendo `disaster.events::notifications.disaster-registered` |
| `disaster.registration.dlq` | Cola (DLQ) | Artemis | Revisión manual |
| `notifications.dlq` | Cola (DLQ) | Artemis | Revisión manual |

- Reintentos: 5 entregas con 2 s entre intentos (`max-delivery-attempts`, `redelivery-delay`); agotados, el mensaje pasa a la DLQ de su destino.
- Otros sistemas suscritos agregan su propia cola dentro de `<multicast>` de `disaster.events` en `infra/artemis/broker.xml`.
- La topología se carga al iniciar el contenedor desde `infra/artemis/broker.xml` (`etc-override/broker.xml`, copiado sobre `etc/broker.xml` al crear la instancia; la imagen no fusiona archivos).

## Propiedades del mensaje JMS

- Cuerpo: `TextMessage` con JSON.
- Reporte: propiedades `reportId` y `contentType=application/json`.
- Evento: propiedad `eventType=DisasterRegistered`.
- La idempotencia usa el `reportId` del cuerpo (o la propiedad `reportId`); Artemis asigna el `JMSMessageID`.

## Reporte entrante (`disaster.registration.in`)

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

## Evento `DisasterRegistered` (tópico `disaster.events`)

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
