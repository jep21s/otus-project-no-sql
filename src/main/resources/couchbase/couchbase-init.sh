#!/usr/bin/env bash
# Инициализация Couchbase: создание bucket и индексов.
# Используется в compose-файлах профилей a2/b2/c2.
set -euo pipefail

CB_HOST="${CB_HOST:-127.0.0.1}"
CB_USER="${CB_USER:-Administrator}"
CB_PASS="${CB_PASS:-password}"
BUCKET="${CB_BUCKET:-orders}"

echo "Waiting for Couchbase at ${CB_HOST}..."
until curl -sS "http://${CB_HOST}:8091/pools" >/dev/null 2>&1; do
  sleep 2
done

echo "Creating bucket '${BUCKET}' if absent..."
/opt/couchbase/bin/couchbase-cli bucket-create \
  -c "${CB_HOST}:8091" -u "${CB_USER}" -p "${CB_PASS}" \
  --bucket "${BUCKET}" --bucket-type couchbase --bucket-ramsize 512 \
  --bucket-replica 1 --enable-flush 1 \
  --wait 2>/dev/null || \
/opt/couchbase/bin/couchbase-cli bucket-edit \
  -c "${CB_HOST}:8091" -u "${CB_USER}" -p "${CB_PASS}" \
  --bucket "${BUCKET}" --bucket-ramsize 512 --enable-flush 1

echo "Creating indexes..."
/opt/couchbase/bin/cbq -e "${CB_HOST}:8093" -u "${CB_USER}" -p "${CB_PASS}" --script="
CREATE PRIMARY INDEX IF NOT EXISTS idx_primary ON \`${BUCKET}\`;
CREATE INDEX IF NOT EXISTS idx_order_userId ON \`${BUCKET}\`(userId) WHERE _class LIKE '%OrderDocument%';
CREATE INDEX IF NOT EXISTS idx_order_userId_created ON \`${BUCKET}\`(userId, createdAt DESC) WHERE _class LIKE '%OrderDocument%';
CREATE INDEX IF NOT EXISTS idx_product ON \`${BUCKET}\`(name) WHERE _class LIKE '%ProductDocument%';
" || true

echo "Couchbase initialization done."
