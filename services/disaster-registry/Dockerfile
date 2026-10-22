FROM quay.io/wildfly/wildfly:38.0.1.Final-jdk17
COPY --chown=jboss:root target/disaster-registry.war /opt/jboss/wildfly/standalone/deployments/disaster-registry.war
COPY --chown=jboss:root wildfly/configure-artemis.cli /tmp/configure-artemis.cli
RUN /opt/jboss/wildfly/bin/jboss-cli.sh --file=/tmp/configure-artemis.cli && rm /tmp/configure-artemis.cli
CMD ["/opt/jboss/wildfly/bin/standalone.sh", "--server-config=standalone-full.xml", "-b", "0.0.0.0", "-bmanagement", "0.0.0.0"]