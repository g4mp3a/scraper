## Component Design: Scraping Worker

| Component | Assumption/Rationale & Trade-Off | Execution Model |
| :--- | :--- | :--- |
| **Processing** | Maximizes Cloud Run concurrency and instantly returns `200 OK` to Pub/Sub, freeing up the network thread. | **@Async Processing** (Detached Thread) |
| **Trade-Off** | If the Cloud Run instance crashes after the controller returns `200 OK` but before the asynchronous job completes, the job is lost and the DB status remains `PROCESSING` indefinitely. | |

---

## Error Handling Strategy

| Strategy | Implementation / Rationale | Rationale |
| :--- | :--- | :--- |
| **Pub/Sub Acknowledgment** | Synchronous `200 OK` Return | The controller immediately returns `200 OK` (success) upon message reception and offloading to the thread pool, as it cannot wait for the final outcome of the detached job. |
| **In-App Retry** | Spring `@Retryable` (on `BingScraper`) | Implemented for **3 retries** on transient network/rate-limit errors, ensuring the job is highly likely to succeed before final failure. |
| **Final Failure Status** | DB status set to `FAILED` | If all in-app retries fail, the `ScrapingService` updates the DB status to `FAILED`. The user must manually check the UI and trigger a re-upload of failed keywords. |
| **Dead Letter Queue (DLQ)** | DLQ is **NOT** implemented for the beta. | **Trade-Off:** Accept that the end-user will handle the retry process for permanent failures in the beta. |

### Future Enhancement

A **Dedicated Retry Topic (DLT)** will be implemented in v1 (using Pub/Sub subscription config) to store final failures for delayed, automated reprocessing, if user feedback demands automation.