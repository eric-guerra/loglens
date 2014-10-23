#!/bin/sh
# Installs the ILM policy and index template, then creates the Kibana data view.
set -e
ES=${ES_URL:-http://elasticsearch:9200}
KIBANA=${KIBANA_URL:-http://kibana:5601}

curl -fsS -X PUT "$ES/_ilm/policy/logs-30d" -H 'Content-Type: application/json' --data-binary @/es/ilm-policy.json
echo
curl -fsS -X PUT "$ES/_index_template/logs" -H 'Content-Type: application/json' --data-binary @/es/logs-template.json
echo

# Kibana may still be starting; retry the data view a few times.
for i in 1 2 3 4 5 6 7 8 9 10; do
  if curl -fsS -X POST "$KIBANA/api/data_views/data_view" -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
      -d '{"data_view":{"title":"logs-*","name":"LogLens logs","timeFieldName":"@timestamp"},"override":true}' >/dev/null; then
    echo "Kibana data view created"
    exit 0
  fi
  sleep 6
done
echo "Kibana not reachable; create the logs-* data view manually"
