#!/bin/sh
#
# Starts WildFly with the database connection and document directory taken from
# environment variables. Nothing sensitive is stored in the image.
#
set -eu

: "${CLINIC_DB_HOST:=postgres}"
: "${CLINIC_DB_PORT:=5432}"
: "${CLINIC_DB_NAME:=clinic_portal}"
: "${CLINIC_DB_USER:=clinic_app}"
: "${CLINIC_DOCUMENT_DIR:=/var/clinic/documents}"

if [ -z "${CLINIC_DB_PASSWORD:-}" ]; then
  echo "CLINIC_DB_PASSWORD is not set. Refusing to start with a default password." >&2
  exit 1
fi

mkdir -p "${CLINIC_DOCUMENT_DIR}"

JDBC_URL="jdbc:postgresql://${CLINIC_DB_HOST}:${CLINIC_DB_PORT}/${CLINIC_DB_NAME}"

echo "Waiting for PostgreSQL at ${CLINIC_DB_HOST}:${CLINIC_DB_PORT}..."
attempt=0
while ! (echo > "/dev/tcp/${CLINIC_DB_HOST}/${CLINIC_DB_PORT}") 2>/dev/null; do
  attempt=$((attempt + 1))
  if [ "${attempt}" -gt 60 ]; then
    echo "PostgreSQL did not become reachable in time." >&2
    exit 1
  fi
  sleep 2
done
echo "PostgreSQL is reachable."

exec /opt/jboss/wildfly/bin/standalone.sh \
  -b 0.0.0.0 -bmanagement 0.0.0.0 \
  -Dclinic.db.url="${JDBC_URL}" \
  -Dclinic.db.user="${CLINIC_DB_USER}" \
  -Dclinic.db.password="${CLINIC_DB_PASSWORD}" \
  -Dclinic.document.dir="${CLINIC_DOCUMENT_DIR}" \
  -Djboss.as.management.blocking.timeout=600
