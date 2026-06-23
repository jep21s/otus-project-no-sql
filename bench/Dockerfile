FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends gcc libpq-dev curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /bench
COPY bench/requirements.txt /bench/requirements.txt
RUN pip install --no-cache-dir -r /bench/requirements.txt

COPY bench /bench
COPY load /load
