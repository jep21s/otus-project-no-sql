#!/bin/bash
# Генерация CA и node-сертификатов с SAN (DNS + опционально статический IP) для Couchbase multi-node.
# Аргументы: host[:ip], напр. gen-certs.sh couchbase-node-1:172.28.0.11 couchbase-node-2:172.28.0.12
set -e
DIR="$(cd "$(dirname "$0")" && pwd)/certs"
mkdir -p "$DIR"

if [ ! -f "$DIR/ca.pem" ]; then
    openssl genrsa -out "$DIR/ca.key" 2048 2>/dev/null
    openssl req -new -x509 -days 3650 -sha256 -key "$DIR/ca.key" -out "$DIR/ca.pem" \
        -subj "/CN=Couchbase Research CA" 2>/dev/null
    echo "[certs] CA generated"
fi

for pair in "$@"; do
    host="${pair%%:*}"
    if [[ "$pair" == *:* ]]; then ip="${pair##*:}"; else ip=""; fi
    san="DNS:${host}, IP:127.0.0.1"
    if [ -n "$ip" ]; then san="${san}, IP:${ip}"; fi
    mkdir -p "$DIR/$host"
    cat > "$DIR/$host/server.ext" <<EOF
basicConstraints=CA:FALSE
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer:always
extendedKeyUsage = serverAuth
keyUsage = digitalSignature,keyEncipherment
subjectAltName = ${san}
EOF
    openssl genrsa -out "$DIR/$host/pkey.key" 2048 2>/dev/null
    openssl req -new -key "$DIR/$host/pkey.key" -out "$DIR/$host/node.csr" -subj "/CN=Couchbase Server" 2>/dev/null
    openssl x509 -CA "$DIR/ca.pem" -CAkey "$DIR/ca.key" -CAcreateserial -days 3650 -req \
        -in "$DIR/$host/node.csr" -out "$DIR/$host/chain.pem" -extfile "$DIR/$host/server.ext" 2>/dev/null
    echo "[certs] $host -> SAN=${san}"
done
echo "[certs] done in $DIR"
