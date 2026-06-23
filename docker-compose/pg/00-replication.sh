#!/bin/bash
set -e
# разрешаем репликационные подключения от repl (нужно для pg_basebackup и streaming replication)
if ! grep -q "host replication repl" "$PGDATA/pg_hba.conf"; then
    echo "host replication repl all trust" >> "$PGDATA/pg_hba.conf"
fi
