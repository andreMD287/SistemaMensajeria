FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM quay.io/wildfly/wildfly:38.0.1.Final-jdk17
COPY --from=build --chown=jboss:root /build/target/notifications.war /opt/jboss/wildfly/standalone/deployments/notifications.war
COPY --chown=jboss:root wildfly/configure-artemis.cli /tmp/configure-artemis.cli
RUN /opt/jboss/wildfly/bin/jboss-cli.sh --file=/tmp/configure-artemis.cli && rm /tmp/configure-artemis.cli
CMD ["/opt/jboss/wildfly/bin/standalone.sh", "--server-config=standalone-full.xml", "-b", "0.0.0.0", "-bmanagement", "0.0.0.0"]
