# SistemaMensajeria – Taller 3

Módulo de un sistema de gestión de emergencias y desastres que **recibe de forma asíncrona** los reportes de un desastre, los **registra** y **notifica** a los sistemas suscritos por mensajería, y a las personas por correo electrónico (JavaMail) y SMS (simulado).

Arquitectura de microservicios Jakarta EE (WildFly 38) con MDB sobre ActiveMQ Artemis, empaquetados con Docker y desplegables en Kubernetes. El diseño está en [`docs/arquitectura.md`](docs/arquitectura.md) y los contratos de mensajes en [`docs/contracts.md`](docs/contracts.md).

```
Sistema emisor / simulador ──> Artemis: cola disaster.registration.in
                                   │
                                   ▼
                         Registro de Desastres (MDB) ──> PostgreSQL (reporte + outbox)
                                   │
                                   ▼
                         Artemis: tópico disaster.events (DisasterRegistered)
                                   │
                 ┌─────────────────┴─────────────────┐
                 ▼                                   ▼
     Notificaciones (MDB)                  Otros sistemas suscritos
     correo (MailHog) + SMS (log)          (cola propia en el tópico)
```

## Estructura

| Carpeta | Contenido |
|---|---|
| `services/disaster-registry/` | Microservicio Registro de Desastres: MDB de reportes, idempotencia por `reportId` y publicador del outbox |
| `services/notifications/` | Microservicio Notificaciones: MDB de eventos, correo con JavaMail y SMS simulado |
| `infra/artemis/broker.xml` | Configuración completa del broker: colas, tópico, reintentos y DLQ |
| `infra/postgres/init/` | Script que crea las tablas `disaster_reports` y `event_outbox` |
| `simulator/` | Simula sistemas emisores: publica reportes JMS (`TextMessage`) en `disaster.registration.in` |
| `jms-probe/` | Herramienta de pruebas JMS: consumo asíncrono, DLQ y suscripción de un sistema externo |
| `k8s/` | Manifiestos de Kubernetes (infraestructura, servicios y Job del simulador) |
| `docs/` | Diseño arquitectónico y contratos |

## Requisitos

- Docker Desktop (con Kubernetes activado para la parte de K8s).
- Para `jms-probe/`: JDK 17+ y Maven. No hace falta Maven para los servicios: sus imágenes compilan el WAR dentro de Docker.

## Ejecución con Docker Compose

```bash
docker compose up -d --build --wait     # Artemis, PostgreSQL, MailHog, Registro y Notificaciones
```

| Servicio | URL |
|---|---|
| Registro de Desastres | http://localhost:8080/disaster-registry/api/health |
| Notificaciones | http://localhost:8081/notifications/api/health |
| MailHog (correos enviados) | http://localhost:8025 |
| Consola de Artemis | http://localhost:8161 (disaster / disaster) |
| PostgreSQL | `localhost:5433`, base `disasters` (disaster / disaster) |

> Si se cambia `infra/artemis/broker.xml`, recrear el contenedor del broker (`docker compose up -d --force-recreate artemis`): la imagen solo copia la configuración al crear la instancia. No usar `docker compose down -v` si se quieren conservar los datos de PostgreSQL.

### Demo del flujo

```bash
# 1. Enviar 3 reportes de desastre
docker compose run --rm simulator --count 3

# 2. Ver los reportes registrados y los eventos publicados
docker exec disaster-postgres psql -U disaster -d disasters -c "select report_id, received_at from disaster_reports order by received_at desc limit 3"
docker exec disaster-postgres psql -U disaster -d disasters -c "select aggregate_id, event_type, published_at from event_outbox order by created_at desc limit 3"

# 3. Ver los correos en http://localhost:8025 y los SMS simulados en el log
docker logs notifications-app 2>&1 | grep -i sms

# 4. Idempotencia: el mismo reporte enviado dos veces se registra una sola vez
docker compose run --rm simulator --count 1 --duplicate
```

Opciones del simulador: `--count N`, `--duplicate` (envía cada reporte dos veces con el mismo `reportId`) e `--interval-ms N`.

### Demo de un sistema externo suscrito

Un sistema nuevo se registra suscribiéndose al tópico `disaster.events` con su propia suscripción durable, sin cambiar Registro ni `broker.xml`:

```bash
cd jms-probe
mvn -q compile dependency:copy-dependencies -DoutputDirectory=target/deps
java -cp "target/classes;target/deps/*" co.edu.javeriana.disaster.probe.JmsProbe subscribe sistema-alertas 3 90
# en otra terminal: docker compose run --rm simulator --count 3
```

En Linux o macOS el separador del classpath es `:` en lugar de `;`. La suscripción es durable: los eventos publicados mientras el sistema externo está apagado lo esperan en su cola. Para eliminarla:
`docker exec disaster-artemis /var/lib/artemis-instance/bin/artemis queue delete --name sistema-alertas --user disaster --password disaster`

### Otras pruebas con `jms-probe`

Con los servicios detenidos (para que no consuman los mensajes antes que la herramienta):

```bash
mvn -q exec:java -Dexec.args="reports 3"   # listener asíncrono sobre disaster.registration.in
mvn -q exec:java -Dexec.args="events 2"    # publica en disaster.events y lee notifications.disaster-registered
mvn -q exec:java -Dexec.args="fail"        # rechaza un reporte hasta que Artemis lo mueve a la DLQ
```

## Ejecución con Kubernetes

```bash
docker compose build disaster-registry notifications simulator   # imágenes locales que usan los manifiestos
kubectl apply -k k8s
kubectl -n disaster get pods                # artemis, postgres, mailhog, disaster-registry y notifications en Running

kubectl create -f k8s/simulator-job.yaml    # Job que publica 5 reportes
kubectl -n disaster port-forward svc/mailhog 8025:8025   # ver los correos en http://localhost:8025
```

`k8s/config/` tiene copias de `infra/artemis/broker.xml` y `infra/postgres/init/001_disaster_registry.sql` (Kustomize solo lee archivos dentro de `k8s/`). Si se cambia uno de esos archivos, copiarlo también a `k8s/config/` para que Compose y Kubernetes usen la misma configuración.

Los manifiestos usan `imagePullPolicy: Never` (imágenes locales de Docker Desktop) y `enableServiceLinks: false`, porque Kubernetes inyecta variables `*_PORT=tcp://...` por cada Service y chocarían con la configuración de los servicios.

Para borrar todo: `kubectl delete namespace disaster`.
