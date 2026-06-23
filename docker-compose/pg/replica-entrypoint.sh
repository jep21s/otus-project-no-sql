#!/bin/bash
# Entrypoint для PG-реплики: при первом старте делает basebackup с master (от postgres),
# затем передаёт управление официальному docker-entrypoint.sh (он стартует postgres как standby).
set -e

if [ ! -s "$PGDATA/PG_VERSION" ]; then
    mkdir -p "$PGDATA"
    chown -R postgres:postgres "$PGDATA"
    echo "[replica] pg_basebackup from pg-master..."
    until su postgres -c "pg_basebackup -h pg-master -p 5432 -U repl -D $PGDATA -X stream -R -c fast --no-password"; do
        echo "[replica] waiting for master..."; sleep 2
    done
fi

exec docker-entrypoint.sh postgres \
    -c hot_standby=on \
    -c max_wal_senders=20 \
    -c max_replication_slots=20 \
    -c wal_level=replica
