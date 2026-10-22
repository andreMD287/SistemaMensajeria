# SistemaMensajeria – Taller 3

Registro y notificación asíncrona de desastres con microservicios Jakarta EE, ActiveMQ Artemis, JavaMail, Docker y Kubernetes.

Flujo: `Simulador → Artemis → Registro de Desastres → PostgreSQL → DisasterRegistered → Notificaciones (MailHog / SMS simulado)`

## Estructura
| Carpeta | Contenido |
|---|---|
| `infra/` | Topología de Artemis (`artemis/broker-00.xml`) y scripts SQL de PostgreSQL |
| `services/disaster-registry/` | Microservicio Registro de Desastres |
| `services/notifications/` | Microservicio Notificaciones |
| `simulator/` | Publica reportes de prueba en `disaster.reports` |
| `jms-probe/` | Prueba de consumo JMS asíncrono sobre Artemis (referencia para el MDB) |
| `k8s/` | Manifiestos de Kubernetes (infraestructura) |
| `docs/` | `contracts.md` (mensajes y topología) y `arquitectura.md` |

## Uso con Docker Compose
```bash
docker compose up -d --wait                        # postgres, artemis, mailhog (+ servicios si están en el override)
docker compose run --rm simulator --count 5        # publica 5 reportes
docker compose run --rm simulator --count 1 --duplicate   # mismo reporte dos veces (idempotencia)
```

| Servicio | URL |
|---|---|
| Artemis consola | http://localhost:8161 (disaster / disaster); broker en `localhost:61616` |
| MailHog UI | http://localhost:8025 (SMTP en `localhost:1025`) |
| PostgreSQL | `localhost:5433`, db `disasters` (disaster / disaster) |

## Uso con Kubernetes
Requiere Kubernetes activo (por ejemplo, Docker Desktop).
```bash
kubectl kustomize --load-restrictor=LoadRestrictionsNone k8s | kubectl apply -f -
kubectl -n disaster get pods
kubectl -n disaster port-forward svc/artemis 8161:8161

# simulador como Job (imagen local)
docker build -t disaster-simulator:latest ./simulator
kubectl create -f k8s/simulator-job.yaml
```
> En los Deployments de los servicios usar `enableServiceLinks: false`: si no, Kubernetes inyecta `*_PORT=tcp://...` y rompe la configuración.

## Prueba JMS
```bash
cd jms-probe
mvn -q exec:java -Dexec.args="reports 3"   # listener asíncrono sobre disaster.registration.in
mvn -q exec:java -Dexec.args="events 2"    # publica en disaster.events y lee la cola de suscripción (FQQN)
mvn -q exec:java -Dexec.args="fail"        # rechaza hasta enviar el mensaje a la DLQ
```
