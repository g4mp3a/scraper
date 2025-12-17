# CDC using Debezium Embedded (The Middle Path)

Debezium provides an `EmbeddedEngine` that allows you to run a Change Data Capture (CDC) connector (like the Postgres connector) inside a standard Java application (our Spring Boot service) without relying on a Kafka Connect cluster.
The complexity of a full Dataproc/Dataflow pipeline is high for a beta, but we can gain the benefits of CDC without the full infrastructure management using Debezium Embedded.

## Pros:

* **No Polling:** It uses the PostgreSQL Logical Replication API, streaming changes in real-time, eliminating latency and database polling strain.
* **Single Service:** It runs within one containerized service (Cloud Run), avoiding the need for a full Kafka/Dataproc cluster.
* **High Reliability:** It uses Debezium's built-in state management to track its position in the replication log, ensuring no events are missed on restart.

## Cons:

* **Setup Complexity:** It requires more complex configuration than simple polling and involves initializing the Debezium engine and handling callbacks.
* **Resource Use:** The instance must be kept running (scaled minimum 1) to maintain the streaming connection, meaning it won't scale to zero.

## Comparison of CDC Approaches

| Feature | Polling Outbox (Simple) | Debezium Embedded (Middle Path) | Debezium on Dataproc (Complex) |
| :--- | :--- | :--- | :--- |
| **Latency** | High (Polling Interval) | Near Real-Time | Near Real-Time |
| **DB Strain** | High (Repeated `SELECT`s) | Low (Streaming) | Low (Streaming) |
| **Services to Manage** | 3 (API, Worker, Poller) | 3 (API, Worker, Publisher) | 2 (API, Worker) + Managed Cluster |
| **Complexity** | Low | Medium | High |
| **Scaling** | Scales to zero (if polled externally) | Runs constantly (min 1 instance) | Scales with cluster |
