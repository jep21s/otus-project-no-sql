#!/bin/sh
# Создание Redis Cluster из 6 нод (3 master + 3 replica).
set -e

NODES="redis-node-1 redis-node-2 redis-node-3 redis-node-4 redis-node-5 redis-node-6"
for n in $NODES; do
    echo "[cluster] waiting for $n..."
    until redis-cli -h "$n" ping >/dev/null 2>&1; do sleep 1; done
done

echo "[cluster] creating cluster..."
redis-cli --cluster create \
    redis-node-1:6379 redis-node-2:6379 redis-node-3:6379 \
    redis-node-4:6379 redis-node-5:6379 redis-node-6:6379 \
    --cluster-replicas 1 --cluster-yes

echo "[cluster] done"
redis-cli -h redis-node-1 cluster info | grep cluster_state
