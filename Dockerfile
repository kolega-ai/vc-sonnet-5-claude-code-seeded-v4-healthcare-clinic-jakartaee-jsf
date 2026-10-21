# ---------------------------------------------------------------------------
# Stage 1 — build the WAR with the committed Maven wrapper.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src
COPY pom.xml ./
# Warm the dependency cache so source-only rebuilds are fast.
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# Fetch the PostgreSQL JDBC driver; it is deployed to WildFly separately so the
# application WAR stays free of server-provided libraries.
RUN mvn -B -q dependency:copy \
      -Dartifact=org.postgresql:postgresql:42.7.3 \
      -DoutputDirectory=/driver \
      -Dmdep.stripVersion=true

# ---------------------------------------------------------------------------
# Stage 2 — WildFly 31 (Jakarta EE 10) on JDK 21.
# ---------------------------------------------------------------------------
FROM quay.io/wildfly/wildfly:31.0.1.Final-jdk21

USER root

# Local document storage. Metadata lives in Postgres; bytes live here.
RUN mkdir -p /var/clinic/documents && chown -R jboss:jboss /var/clinic

COPY --chown=jboss:jboss docker/entrypoint.sh /opt/clinic/entrypoint.sh
RUN chmod +x /opt/clinic/entrypoint.sh

# The JDBC driver is deployed as a plain JAR: WildFly detects it through the
# JDBC 4 service loader and registers it under the deployment name.
COPY --from=build --chown=jboss:jboss /driver/postgresql.jar \
     /opt/jboss/wildfly/standalone/deployments/postgresql.jar

# Datasource definition (credentials come from the entrypoint, not the image).
COPY --chown=jboss:jboss docker/clinic-ds.xml \
     /opt/jboss/wildfly/standalone/deployments/clinic-ds.xml

# The application itself.
COPY --from=build --chown=jboss:jboss /src/target/clinic-portal.war \
     /opt/jboss/wildfly/standalone/deployments/clinic-portal.war

USER jboss

ENV CLINIC_DOCUMENT_DIR=/var/clinic/documents \
    JAVA_OPTS="-Xms256m -Xmx1024m -Djava.net.preferIPv4Stack=true -Djava.awt.headless=true"

EXPOSE 8080 9990

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
  CMD curl -fsS http://localhost:8080/login.xhtml > /dev/null || exit 1

ENTRYPOINT ["/bin/bash", "/opt/clinic/entrypoint.sh"]
