# 🔎 Bing Keyword Scraper WebApp: Consolidated Design Document

This web application allows authenticated users to upload keyword lists and **asynchronously** scrape search results from bing.com. 
The system is designed for **high reliability, scalability, and cost efficiency** using GCP serverless components.

---

## System Architecture Overview

The core backend relies heavily on a serverless microservices pattern hosted on Google Cloud Run, orchestrated by an event-driven architecture.

### Consolidated System Components

This table maps the functional components of the architecture to the technologies and Google Cloud Platform services utilized, 
detailing the primary role of each.

| Component | Technology | GCP Service | Role |
| :--- | :--- | :--- | :--- |
| **API Service** | Spring Boot (Kotlin) | Cloud Run 1 | Handles all user requests, security (Firebase JWT), file uploads, and atomic writes to the Outbox. |
| **CDC Publisher** | Debezium Embedded | Cloud Run 3 | Real-Time Event Streamer. Reads the PostgreSQL replication log (`outbox_event` table) and publishes events to Pub/Sub instantly. |
| **Scraping Worker** | Ktor Client, Jsoup | Cloud Run 2 | Pub/Sub subscriber. Performs high-concurrency, anti-rate-limit web scraping and updates the database with results. |
| **Messaging** | Event Queue | Cloud Pub/Sub | Decouples services, ensuring message durability. |
| **Authentication** | JWT/OIDC | Firebase Authentication | Identity Provider; manages user credentials and token issuance. |
| **Persistence** | PostgreSQL | Cloud SQL | Managed, highly available, transactional data store. |

#### Backend Services Core Responsibilities

| Service | Key Libraries/Tech | Core Responsibility |
| :--- | :--- | :--- |
| **API Service** | Spring Security, Firebase Admin, Liquibase | Authentication, File Parsing, Transactional Write to Outbox. |
| **CDC Publisher** | Debezium Embedded, Pub/Sub Client, GSON | Read PostgreSQL Logical Replication Stream, Publish Events. |
| **Scraping Worker** | Ktor Client, Jsoup, Spring Retry, `@Async` | Anti-Rate-Limit HTTP Calls, HTML Parsing, Database Result Update. |

### Technology Stack and GCP Service Mapping

| Layer | Technology | GCP Service | Role                                                                               |
| :--- | :--- | :--- |:-----------------------------------------------------------------------------------|
| **Backend Logic** | Spring Boot (Kotlin) | **Cloud Run** | Hosts the three core backend services (API, Publisher, Worker).                    |
| **Persistence** | PostgreSQL | **Cloud SQL** | Managed, highly available, transactional data store.                               |
| **Authentication** | JWT/OIDC | **Firebase Authentication** | Identity Provider; manages user credentials and token issuance.                    |
| **Messaging** | Event Queue | **Cloud Pub/Sub** | Decouples services, ensures message durability, and triggers the Worker.           |
| **CDC / Outbox** | Debezium Embedded Engine | **Cloud Run (Publisher Service)** | Provides real-time event streaming from PostgreSQL to Pub/Sub.                     |
| **Scraping** | Ktor Client, Jsoup, Spring Retry | **Cloud Run (Worker Service)** | High-concurrency, and anti-rate-limit techniques employed in background processor. |

---

## Key Architectural Decisions and Trade-offs

| Area | Decision | Rationale & Trade-offs                                                                                                                                                                                                                                                                                                                                                                                                                |
| :--- | :--- |:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Asynchronous Core** | Transactional Outbox Pattern + Pub/Sub. | **Crucial for stability.** Guarantees that job creation and event publishing are atomic. The user API returns `202 Accepted` immediately, preventing timeouts.                                                                                                                                                                                                                                                                        |
| **CDC Implementation** | Debezium Embedded Engine on a dedicated Cloud Run service (**Publisher**). | **Optimal Choice:** Avoids the latency and DB strain of polling the `outbox_event` table while avoiding the complexity of a full Dataproc/Kafka cluster setup. Streams changes in real-time.                                                                                                                                                                                                                                          |
| **Worker Execution** | `@Async` Processing in Scraping Worker. | **Trade-off for Beta:** Maximizes Cloud Run <br/>concurrency and throughput by instantly freeing the HTTP thread. **Known Risk:** If the container crashes after acknowledging the message (`200 OK`) but before the job completes and writes search results to DB, the job is lost and remains stuck as `PROCESSING`. User must manually retry failed jobs. Future versions can employ websockets of SSE depending on user feedback. |
| **Reliability/Retry** | Spring `@Retryable` (3 attempts) + custom `PermanentScrapingFailureException`. | Handles transient rate limits and network issues internally before marking the job as `FAILED` in the database, providing controlled recovery.                                                                                                                                                                                                                                                                                        |
| **Security** | Stateless JWTs validated by a custom Spring Security filter using the Firebase Admin SDK. | Ensures API security while enabling horizontal scaling of all services on Cloud Run. CSRF disabled; sessions are stateless.                                                                                                                                                                                                                                                                                                           |
| **Frontend UX** | Manual "Refresh" button (instead of polling). | **Cost-Efficiency:** Eliminates continuous, unnecessary polling of the backend, allowing Cloud Run instances to scale to zero for idle users.                                                                                                                                                                                                                                                                                         |

---

## Publisher Service - Comparison of CDC Approaches

Trade-offs between three common patterns for implementing Change Data Capture (CDC) with an Outbox.

| Feature | Polling Outbox (Simple) | Debezium Embedded (Middle Path) | Debezium on Dataproc (Complex) |
| :--- | :--- | :--- | :--- |
| **Latency** | High (Polling Interval) | Near Real-Time | Near Real-Time |
| **DB Strain** | High (Repeated `SELECT`s) | Low (Streaming) | Low (Streaming) |
| **Services to Manage** | 3 (API, Worker, Poller) | 3 (API, Worker, Publisher) | 2 (API, Worker) + Managed Cluster |
| **Complexity** | Low | Medium | High |
| **Scaling** | Scales to zero (if polled externally) | Runs constantly (min 1 instance) | Scales with cluster |

**The beta version adopts the middle path of using the embedded debezium engine.**

---

## Scraping Worker Technology and Design Choices

Scraping worker design uses Cloud Run and Pub/Sub subscriptions to enable a consumption-based auto-scaling design.

| Feature | Impact                                                                                                                 |
| :--- |:-----------------------------------------------------------------------------------------------------------------------|
| **No Provisioning** | You never worry about server capacity or provisioning Virtual Machines (VMs).                                          |
| **Pay-per-Use** | You only pay for the CPU time consumed while the workers are actively scraping or waiting for the Bing API response.   |
| **Burstable Traffic** | The system is designed to handle massive spikes in file uploads and then settle back down, making it highly resilient. |

### `@Async`
Workers run the potentially time heavy scraping jobs asynchronously using Spring Boot's `@Async` freeing the main 
thread to handle events from queue.

#### Drawbacks of sync strategy
In a synchronous design, the Cloud Run instance that receives the push request must stay alive and busy 
for the entire duration of the scraping job (which includes network requests, I/O, and the randomized delay of 1-3 seconds).

* **High Latency:** The HTTP request from Pub/Sub takes several seconds to complete, tying up the Cloud Run instance.
* **Increased Cost:** We pay for the CPU time, including the time spent waiting during the randomized delays (`delay(delayTime)`).
* **Concurrency Blockage:** The instance's concurrent request capacity is blocked by one long-running request, potentially throttling the message processing rate.

### Anti-Rate-Limit Strategy
The Scraper rotates agents between jobs and introduces a random delay when making GET requests to bing.com to perform keyword seaches.
* User-Agent: Use a realistic, rotating pool of User-Agent strings (desktop/mobile).
* Randomized Delay: Implement a randomized delay (e.g., 5 to 15 seconds) between successive requests within the 
  worker service to mimic human behavior.
* Proxy Rotation (Future): For higher volume, integrate a third-party rotating proxy service. For the beta, we rely 
  on randomized delay and User-Agents.

### Design Trade-Offs 
| Component | Design/Assumption | Rationale & Trade-Off |
| :--- | :--- | :--- |
| **Execution Model** | `@Async` Processing (Detached Thread) | **Pro:** Maximizes Cloud Run concurrency and instantly returns `200 OK` to Pub/Sub, freeing up the network thread. **Con (Trade-Off):** If the Cloud Run instance crashes after the controller returns `200 OK` but before the asynchronous job completes, the job is lost and the DB status remains `PROCESSING` indefinitely. |
| **Error Handling (Controller)** | Synchronous `200 OK` Return | The controller immediately returns `200 OK` (success) upon message reception and offloading to the thread pool, as it cannot wait for the final outcome. |
| **In-App Retry** | Spring `@Retryable` | Implemented in `BingScraper` for **3 retries** on transient network/rate-limit errors, ensuring the job is highly likely to succeed before final failure. |
| **Final Failure Status** | DB status set to `FAILED` | If all in-app retries fail, the `ScrapingService` updates the DB to `status=FAILED`. The user must manually check the UI and trigger a re-upload of failed keywords. |
| **Dead Letter Queue (DLQ)** | DLQ is **NOT** implemented for the beta. | **Trade-Off:** Accept that the end-user will handle the retry process for permanent failures in the beta. |
| **Future Enhancement** | Dedicated Retry Topic (DLT) | If user feedback demands automation, a DLT will be implemented in v1 (using Pub/Sub subscription config) to store final failures for delayed reprocessing. |

---

## Data Model (Key Tables)

| Table | Purpose | Indexing |
| :--- | :--- | :--- |
| `app_user` | Stores local user data, linked to Firebase UID. | **PK** on `firebase_uid`. |
| `keyword_search` | Stores job metadata, status (`search_status ENUM`), and results. | Index on `(app_user_id, created_at DESC)` for fast user-specific retrieval. |
| `outbox_event` | Stores events to be published (Transactional Outbox). | Index on `(id WHERE processed_at IS NULL)` for CDC resumption. |

### Job Status (`search_status ENUM`) Definitions

| Value | Description |
| :--- | :--- |
| **PENDING** | Job created, event queued in Outbox. |
| **PROCESSING** | Event consumed by Worker, scraping in progress. |
| **COMPLETED** | Scraping finished, results saved. |
| **FAILED** | Scraping failed after all internal retries. |

### Database Index Analysis

#### Required Query Patterns (Reads)

This outlines the essential data retrieval patterns necessary to support both the user-facing API and the internal CDC workflow.

| # | Requirement | Query Type | Data Fields Involved |
| :--- | :--- | :--- | :--- |
| **1.** | View list of keywords for the user (UI/API) | Fetch list, ordered by time. | `app_user_id`, `created_at` |
| **2.** | View search result for a specific keyword ID | Fetch single record by ID, secure check for user. | `id`, `app_user_id` |
| **3.** | Search across all reports by keyword | Fetch list by user and keyword (`LIKE` search). | `app_user_id`, `keyword` |
| **4.** | CDC Connector processing | Find events that haven't been published. | `processed_at` (`IS NULL`) |

#### Database Indexing Strategy

The following indexes and constraints are implemented to ensure fast lookups and high performance for the most common query patterns (Reads 1-4).

| Table | Index / Constraint | Fields Covered | Status | Rationale |
| :--- | :--- | :--- | :--- | :--- |
| `app_user` | Primary Key | `firebase_uid` | ✅ OK | PK is indexed, covering the join key (`keyword_search.app_user_id`). |
| `keyword_search` | Primary Key | `id` | ✅ OK | PK index covers lookups by ID (Query 2). |
| `keyword_search` | Custom Index (`idx_keyword_search_user`) | `app_user_id`, `created_at DESC` | ✅ Crucial | Directly supports Query 1 and is highly efficient for Query 3 when ordering/filtering by user (the most common API pattern). |
| `outbox_event` | Primary Key | `id` | ✅ OK | Standard. |
| `outbox_event` | Custom Index (`idx_outbox_event_unprocessed`) | `id` WHERE `processed_at IS NULL` | ✅ Crucial | Directly supports Query 4 (the CDC worker's sole read operation) and provides maximum efficiency for this critical task. |

Note that there is no index created on `keyword` for Query 3 (LIKE '%search%') because PostgreSQL is often very 
efficient at handling LIKE searches without a full index, and adding an index on a frequently updated text column 
would dramatically slow down write operations. Rely on the app_user_id index and PostgreSQL's built-in query 
planning for that secondary search.