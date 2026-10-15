#!/usr/bin/env bash
# Creates the Ops-Agent topics with explicit partitions and retention. Safe to run repeatedly.
# Partition counts are the lite-profile values from docs/project-design.md (Kafka topics).
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP_SERVERS:-kafka:29092}"
TOPICS=/opt/kafka/bin/kafka-topics.sh

DAY_MS=86400000

until "$TOPICS" --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; do
  echo "waiting for Kafka at $BOOTSTRAP ..."
  sleep 2
done

# create <topic> <partitions> <extra --config arguments...>
create() {
  local topic="$1" partitions="$2"
  shift 2
  "$TOPICS" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions "$partitions" --replication-factor 1 "$@"
}

retention() { echo "--config retention.ms=$(( $1 * DAY_MS ))"; }

# shellcheck disable=SC2046
{
  create logs.raw              6 $(retention 7)
  create logs.normalized       6 $(retention 3)
  create logs.unparsed         1 $(retention 7)
  create rules.changelog       1 --config cleanup.policy=compact
  create topology.changelog    1 --config cleanup.policy=compact
  create alerts                6 $(retention 7)
  create incident.candidates   6 $(retention 7)
  create incident.events       3 $(retention 30)
  create agent.tasks.high      3 $(retention 3)
  create agent.tasks.low       3 $(retention 3)
  create agent.results         3 $(retention 7)

  # Dead-letter topics for Spring Kafka consumers (<topic>.dlt).
  for t in logs.normalized alerts incident.candidates agent.results agent.tasks; do
    create "$t.dlt" 1 $(retention 14)
  done

  # Dead-letter topic for records Kafka Streams cannot deserialize.
  create stream-processor.dlt  1 $(retention 14)
}

echo "topics:"
"$TOPICS" --bootstrap-server "$BOOTSTRAP" --list
