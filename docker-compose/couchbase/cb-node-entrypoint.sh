#!/bin/sh
# Wrapper-entrypoint для Couchbase-ноды: копирует node-сертификаты из /staged/<hostname>/
# в inbox с правильным владельцем (couchbase), затем запускает оригинальный entrypoint.
# Сертификаты монтируются общим каталогом в /staged (read-only).
set -e
HOST=$(hostname)
INBOX=/opt/couchbase/var/lib/couchbase/inbox
mkdir -p "$INBOX/CA"
if [ -f "/staged/$HOST/chain.pem" ] && [ -f "/staged/$HOST/pkey.key" ] && [ -f "/staged/ca.pem" ]; then
    cp /staged/$HOST/chain.pem "$INBOX/chain.pem"
    cp /staged/$HOST/pkey.key "$INBOX/pkey.key"
    cp /staged/ca.pem "$INBOX/CA/ca.pem"
    chown -R couchbase:couchbase "$INBOX"
    chmod 0700 "$INBOX/chain.pem" "$INBOX/pkey.key"
    echo "[cb-entrypoint] staged node cert for $HOST"
else
    echo "[cb-entrypoint] WARNING: no staged cert for $HOST"
fi
exec /entrypoint.sh couchbase-server
