# LogLens

A centralized log pipeline in Java. Shippers (Logstash) push raw lines onto a **Redis** list; LogLens workers parse them with Grok patterns, enrich them, evaluate alert rules, and **bulk-index into Elasticsearch** for exploration in **Kibana**.

> Portfolio reference implementation of a 2014 ELK-stack project, built with Java 21 and Elasticsearch 8.

```
app hosts ──Logstash shipper──▶ Redis list "logs:incoming" ──▶ LogLens workers ──_bulk──▶ Elasticsearch ──▶ Kibana
                                                                   │  parse · enrich · alert
                                                                   ├──▶ webhook (Slack) alerts
                                                                   └──▶ "logs:incoming:dead" (rejected docs + reason)
```

## Components

| Package | What it does |
|---|---|
| `parse` | A **Grok** engine (Logstash `%{PATTERN:field:type}` syntax, nested base patterns) with nginx/Apache combined, Java app log and syslog formats, plus JSON detection. Year-less syslog timestamps are inferred correctly across New Year. Unmatched lines are kept with `_grokparsefailure` |
| `enrich` | HTTP outcome and status class, URL path/query/extension, user-agent family/OS/bot detection, internal-network flag, and **secret redaction** (`password=`, `token=`, `api_key=`…) before anything is indexed |
| `alert` | Rules over one-second-bucket **sliding windows** (O(1) per event, fixed memory): count thresholds, optional minimum ratio (e.g. ≥20 5xx *and* ≥5% of traffic), cooldowns; Slack-compatible webhook |
| `sink` | Elasticsearch `_bulk` into daily indices. Handles **partial failures**: per-item 429/5xx are retried with exponential backoff and jitter, 4xx mapping errors go to the dead-letter list |
| `broker` | Redis consumer using `BLPOP` + `LPOP count` batch drain; an in-memory queue for tests |
| `pipeline` | Worker pool, metrics, dead-lettering; `/health` and `/metrics` on :9100 |

The `elasticsearch/` folder holds the index template (ECS-style field types, `ip`, `wildcard` for URLs) and a 30-day ILM policy; `setup.sh` installs them and creates the Kibana data view.

## Run

```bash
docker compose up --build
# Kibana: http://localhost:5601  (data view "LogLens logs")
# Metrics: curl localhost:9100/metrics
```

The shipper ingests `samples/*.log`. To try it by hand: `redis-cli RPUSH logs:incoming '{"message":"...","source":"manual"}'`.

## Test

```bash
mvn test
```

The tests cover Grok typing and nesting, all three log formats plus JSON, enrichers (redaction, UA families, private ranges), sliding-window expiry, ratio rules and cooldowns, the bulk sink against a scripted fake `_bulk` endpoint (partial failures, retry exhaustion, permanent errors), and an end-to-end pipeline batch plus a multi-worker queue drain.
