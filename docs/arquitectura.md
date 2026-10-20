# Arquitectura – Taller 3: registro y notificación asíncrona de desastres

## 1. Contexto y alcance

El sistema recibe de forma asíncrona reportes de desastres enviados por sistemas externos (entidades de emergencias), los registra y notifica a los sistemas y personas interesadas mediante mensajería, correo electrónico o SMS.

- **Estilo elegido:** microservicios con integración orientada a eventos.
- **Tecnologías exigidas por el enunciado:** broker de mensajería (RabbitMQ), JavaMail, Docker y, por ser microservicios, Kubernetes. El consumo se hace con MDB sobre JMS.
- **Fuera de alcance:** evaluación de daños, personas afectadas, reconstrucción y GIS. Los diferimos porque el taller se valida con un flujo vertical.

## 2. Requisitos arquitectónicamente significativos

### Funcional primario
| ID | Caso de uso |
|---|---|
| CU-1 | Un sistema externo envía un reporte de desastre; el sistema lo registra y publica `DisasterRegistered`; los suscritos y Notificaciones reaccionan. |

### Escenarios de atributos de calidad
| ID | Atributo | Escenario (fuente → estímulo → artefacto → entorno → respuesta → medida) |
|---|---|---|
| QA-1 | Disponibilidad / tolerancia a fallos | Un sistema emisor envía reportes → Registro de Desastres está caído → operación normal → los reportes se conservan en la cola y se procesan al volver el servicio → 0 reportes perdidos. |
| QA-2 | Modificabilidad | Un nuevo sistema quiere recibir los desastres → se agrega una cola enlazada a `disaster.events` → desarrollo → no se modifica ningún servicio existente → cambio de configuración, 0 líneas de código en Registro. |
| QA-3 | Confiabilidad / idempotencia | El broker reentrega un reporte → Registro lo recibe de nuevo → operación normal → no se crea un segundo desastre ni un segundo evento → 100 % de reportes con `reportId` repetido producen un único registro. |
| QA-4 | Rendimiento (latencia) | Llegan 100 reportes en 10 s → carga normal → registro y notificación → 95 % de las notificaciones se emiten en menos de 5 s. *(valor objetivo a validar con medición)* |
| QA-5 | Desacoplamiento del canal | El correo (SMTP) falla → Notificaciones intenta enviar → entorno degradado → el mensaje se reintenta y, agotados los reintentos, pasa a la DLQ sin bloquear otros → 0 mensajes bloqueados. |

### Restricciones
- RC-1: Docker para empaquetar; Kubernetes para el despliegue de microservicios.
- RC-2: el consumo debe poder hacerse con un MDB (a confirmar con el profesor).
- RC-3: el correo se envía con JavaMail.

**Diferido con justificación:** la seguridad (autenticación de emisores, cifrado del canal) se pospone porque el taller se valida en un entorno local de desarrollo; las credenciales son de desarrollo y están declaradas como tales.

## 3. Vista de contenedores (C&C)

```mermaid
flowchart LR
  EXT[Sistemas emisores<br/>IDEAM SGC Bomberos]
  subgraph K8S[Kubernetes - namespace disaster]
    RMQ[(RabbitMQ<br/>exchanges y colas)]
    REG[Registro de Desastres<br/>Jakarta EE - MDB]
    NOT[Notificaciones<br/>Jakarta EE - MDB + JavaMail]
    PG[(PostgreSQL)]
    MH[MailHog SMTP]
  end
  SUB[Otros sistemas suscritos]
  SMS[SMS simulado]
  EXT -->|reporte JSON| RMQ
  RMQ -->|disaster.registration.in| REG
  REG -->|persiste| PG
  REG -->|DisasterRegistered| RMQ
  RMQ -->|notifications.disaster-registered| NOT
  RMQ -->|cola propia| SUB
  NOT -->|correo SMTP| MH
  NOT -.->|log| SMS
```

| Elemento | Responsabilidad |
|---|---|
| Sistema emisor / simulador | Publica el reporte en `disaster.reports`. |
| RabbitMQ | Transporta reportes y eventos; aísla a emisores, Registro y Notificaciones. |
| Registro de Desastres | Valida, deduplica por `reportId`, persiste y publica `DisasterRegistered`. |
| Notificaciones | Consume el evento y envía correo (JavaMail) y SMS (simulado). |
| PostgreSQL | Fuente de verdad de los desastres registrados. |
| MailHog | Servidor SMTP de pruebas. |

## 4. Vista de comportamiento

```mermaid
sequenceDiagram
  participant S as Sistema emisor
  participant X as RabbitMQ
  participant R as Registro de Desastres
  participant D as PostgreSQL
  participant N as Notificaciones
  participant M as MailHog
  S->>X: publica reporte (disaster.reports, report.received)
  X->>R: entrega a disaster.registration.in
  R->>D: INSERT si reportId no existe
  alt reportId ya registrado
    R-->>X: ack sin publicar evento
  else nuevo reporte
    R->>X: publica DisasterRegistered (disaster.events)
    R-->>X: ack
  end
  X->>N: entrega a notifications.disaster-registered
  N->>M: correo por SMTP (JavaMail)
  N->>N: SMS simulado en log
  N-->>X: ack
  Note over R,N: Si falla tras los reintentos el mensaje va a la DLQ
```

## 5. Vista de despliegue

- **Desarrollo:** `docker-compose.yml` levanta RabbitMQ, PostgreSQL y MailHog; los servicios Jakarta se agregan al mismo archivo.
- **Entrega (microservicios):** `k8s/` contiene los manifiestos (StatefulSets para RabbitMQ y PostgreSQL, Deployment para MailHog, ConfigMap con la topología). Cada microservicio se agrega como Deployment + Service.
- Ambos usan la misma topología de RabbitMQ (`infra/rabbitmq/definitions.json`).

## 6. Decisiones arquitectónicas

### D-1. Microservicios con integración por eventos
- **Drivers:** QA-1, QA-2, RC-1.
- **Alternativas:** monolito en capas; servicios/tiers en un contenedor Jakarta.
- **Decisión:** dos microservicios, Registro y Notificaciones, que solo se comunican por eventos.
- **Beneficio:** cada servicio se despliega, escala y falla por separado; agregar suscriptores no toca a Registro (QA-2).
- **Costo:** mayor complejidad operativa (broker, dos despliegues, consistencia eventual) y más difícil de depurar que un monolito.

### D-2. Mensajería asíncrona con RabbitMQ (exchange directo + exchange de eventos tipo topic)
- **Drivers:** QA-1, QA-2, QA-5.
- **Alternativas:** Kafka (retención y reproducción del log, mayor carga operativa para el volumen del taller); MQTT (orientado a dispositivos IoT, pocas garantías de enrutamiento).
- **Decisión:** RabbitMQ con colas durables y mensajes persistentes.
- **Beneficio:** enrutamiento flexible por exchange, colas por suscriptor, DLX nativo.
- **Costo:** no hay reproducción histórica de eventos como en Kafka; el broker es un punto de fallo que habría que clusterizar en producción.
- **Estado:** aceptada.

### D-3. Consumo con MDB sobre JMS (cliente JMS de RabbitMQ)
- **Drivers:** RC-2.
- **Alternativas:** cliente AMQP directo; broker JMS nativo (ActiveMQ Artemis); Kafka con puente.
- **Decisión:** MDB con `rabbitmq-jms` como proveedor JMS, conservando RabbitMQ.
- **Evidencia:** la prueba `jms-probe/` consume desde Java por JMS la cola AMQP existente. Hallazgo: los mensajes publicados por AMQP llegan como `BytesMessage`, no `TextMessage`; el MDB debe tratar ambos tipos. `JMSMessageID` coincide con el `reportId`.
- **Costo:** dependencia adicional y configuración del resource adapter en Payara; **falta validar el MDB dentro de Payara**, no solo con cliente standalone.
- **Estado:** pendiente de confirmar con el profesor que el MDB es obligatorio y que esta opción es aceptable. Plan B: ActiveMQ Artemis.

### D-4. Idempotencia por `reportId`
- **Drivers:** QA-3.
- **Decisión:** `reportId` único en la tabla de desastres; si ya existe, se confirma el mensaje sin publicar evento.
- **Costo:** una consulta/restricción por mensaje; un `reportId` mal generado por el emisor haría que se descarten reportes distintos.

### D-5. Reintentos y cola de mensajes fallidos (DLX/DLQ)
- **Drivers:** QA-1, QA-5.
- **Decisión:** `disaster.dlx` con `disaster.registration.dlq` y `notifications.dlq`; las colas de entrada rechazan hacia ellas.
- **Costo:** los mensajes en DLQ requieren monitoreo y reproceso manual.

### D-6. Correo con JavaMail hacia MailHog; SMS simulado
- **Drivers:** RC-3, QA-5.
- **Decisión:** el correo se envía por SMTP real a MailHog; el SMS se registra en log.
- **Costo:** el SMS no se prueba contra un proveedor real.

### D-7. Persistencia en PostgreSQL por servicio
- **Decisión:** solo Registro accede a la base de datos; Notificaciones no comparte esquema.
- **Costo:** si Notificaciones necesita datos adicionales debe recibirlos en el evento.

## 7. Riesgos y puntos de sensibilidad

| ID | Riesgo | Mitigación |
|---|---|---|
| R-1 | MDB con `rabbitmq-jms` no funciona dentro de Payara. | Probar el MDB temprano; plan B con Artemis. |
| R-2 | Registro cae después de persistir y antes de publicar el evento; el desastre queda sin notificar. | Publicar con confirmaciones y reprocesar; patrón outbox como mejora si se justifica. |
| R-3 | Un solo nodo de RabbitMQ es punto único de fallo. | Aceptado en el taller; clúster en un entorno real. |
| R-4 | Credenciales de desarrollo en los manifiestos. | Declarado como diferido (seguridad). |

## 8. Trazabilidad

| Escenario | Decisión | Elemento | Evidencia |
|---|---|---|---|
| QA-1 | D-1, D-2, D-5 | RabbitMQ, colas durables | Prueba: detener Registro, publicar, reiniciar |
| QA-2 | D-1, D-2 | `disaster.events` + cola por suscriptor | Prueba: agregar una cola y verificar recepción |
| QA-3 | D-4 | Registro, PostgreSQL | Simulador con `--duplicate` |
| QA-4 | D-2 | RabbitMQ, servicios | Medición pendiente |
| QA-5 | D-5, D-6 | Notificaciones, DLQ | Prueba: apagar MailHog |
