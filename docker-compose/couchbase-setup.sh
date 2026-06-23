#!/usr/bin/env bash
# Настройка Couchbase multi-node кластера в compose (secure-join через TLS).
# Ключевое: server-add идёт на HTTPS-порт 18091 с FQDN; ноды имеют сертификаты от общего CA.
# Env: CB_NODES (csv FQDN нод, первый — мастер), CB_BUCKET, CB_USER, CB_PASS, CB_RAMSIZE
set -euo pipefail

IFS=',' read -ra HOSTS <<< "${CB_NODES:-couchbase}"
MASTER="${HOSTS[0]}"
BUCKET="${CB_BUCKET:-orders}"
ADMIN="${CB_USER:-Administrator}"
PASS="${CB_PASS:-password}"
RAM="${CB_RAMSIZE:-1024}"

CLI="/opt/couchbase/bin/couchbase-cli"

wait_ui() {
  local host="$1"
  echo "[setup] waiting for ${host}..."
  for _ in $(seq 1 120); do
    if curl -sS "http://${host}:8091/pools" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "[setup] ERROR: ${host} not reachable" >&2; exit 1
}

# 1. ждём все ноды
for h in "${HOSTS[@]}"; do wait_ui "$h"; done

# 2. деплоим CA + node-сертификат на каждую ноду (контроллеры доступны без auth на свежей ноде)
for h in "${HOSTS[@]}"; do
  echo "[setup] loadTrustedCAs + reloadCertificate on ${h}"
  curl -sS -X POST "http://${h}:8091/node/controller/loadTrustedCAs" >/dev/null 2>&1 || true
  curl -sS -X POST "http://${h}:8091/node/controller/reloadCertificate" >/dev/null 2>&1 || true
  sleep 1
done

# 3. cluster-init мастера
echo "[setup] cluster-init ${MASTER}"
${CLI} cluster-init --cluster "http://${MASTER}:8091" \
  --cluster-username "${ADMIN}" --cluster-password "${PASS}" \
  --cluster-ramsize "${RAM}" --services data,index,query || true

# 4. server-add остальных нод ЧЕРЕЗ HTTPS-порт 18091 (FQDN обязателен)
for h in "${HOSTS[@]:1}"; do
  echo "[setup] server-add ${h}:18091"
  ${CLI} server-add --cluster "http://${MASTER}:8091" \
    --username "${ADMIN}" --password "${PASS}" \
    --server-add "${h}:18091" \
    --server-add-username "${ADMIN}" --server-add-password "${PASS}" \
    --services data,index,query || true
done

# 5. rebalance
if [ "${#HOSTS[@]}" -gt 1 ]; then
  echo "[setup] rebalance"
  ${CLI} rebalance --cluster "http://${MASTER}:8091" --username "${ADMIN}" --password "${PASS}" || true
fi

# 6. bucket
REPLICAS=$((${#HOSTS[@]} - 1)); [ "${REPLICAS}" -gt 3 ] && REPLICAS=3
echo "[setup] bucket ${BUCKET} (replicas=${REPLICAS})"
${CLI} bucket-create --cluster "http://${MASTER}:8091" \
  --username "${ADMIN}" --password "${PASS}" \
  --bucket "${BUCKET}" --bucket-type couchbase --bucket-ramsize 768 \
  --bucket-replica "${REPLICAS}" --enable-flush 1 --wait 2>/dev/null || \
${CLI} bucket-edit --cluster "http://${MASTER}:8091" \
  --username "${ADMIN}" --password "${PASS}" \
  --bucket "${BUCKET}" --bucket-ramsize 768 --enable-flush 1

# 7. autofailover
${CLI} setting-autofailover --cluster "http://${MASTER}:8091" \
  --username "${ADMIN}" --password "${PASS}" \
  --enable-auto-failover 1 --auto-failover-time 5 2>/dev/null || true

echo "[setup] Couchbase cluster ready: ${CB_NODES} (bucket=${BUCKET}, nodes=${#HOSTS[@]})"
