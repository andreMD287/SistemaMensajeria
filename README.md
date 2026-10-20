# SistemaMensajeria – Taller 3

Registro y notificación asíncrona de desastres con microservicios Jakarta EE, RabbitMQ, JavaMail, Docker y Kubernetes.

Flujo: `Simulador → RabbitMQ → Registro de Desastres → PostgreSQL → DisasterRegistered → Notificaciones (MailHog / SMS simulado)`

## Estructura
| Carpeta | Contenido |
|---|---|
| `infra/` | Topología de RabbitMQ (`definitions.json`) y scripts SQL de PostgreSQL |
| `services/disaster-registry/` | Microservicio Registro de Desastres |
| `services/notifications/` | Microservicio Notificaciones |
| `simulator/` | Publica reportes de prueba en `disaster.reports` |
| `jms-probe/` | Prueba de consumo JMS sobre RabbitMQ (referencia para el MDB) |
| `k8s/` | Manifiestos de Kubernetes (infraestructura) |
| `docs/` | `contracts.md` (mensajes y topología) y `arquitectura.md` |

## Uso con Docker Compose
```bash
docker compose up -d --wait                        # postgres, rabbitmq, mailhog (+ servicios si están en el override)
docker compose run --rm simulator --count 5        # publica 5 reportes
docker compose run --rm simulator --count 1 --duplicate   # mismo reporte dos veces (idempotencia)
```

| Servicio | URL |
|---|---|
| RabbitMQ UI | http://localhost:15672 (disaster / disaster) |
| MailHog UI | http://localhost:8025 (SMTP en `localhost:1025`) |
| PostgreSQL | `localhost:5433`, db `disasters` (disaster / disaster) |

## Uso con Kubernetes
Requiere Kubernetes activo (por ejemplo, Docker Desktop).
```bash
kubectl kustomize --load-restrictor=LoadRestrictionsNone k8s | kubectl apply -f -
kubectl -n disaster get pods
kubectl -n disaster port-forward svc/rabbitmq 15672:15672
```

## Prueba JMS
```bash
cd jms-probe && mvn -q exec:java -Dexec.args=3   # consume 3 mensajes de disaster.registration.in
```
