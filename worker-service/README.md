## Component Design: Scraping Worker

| Component               | Assumption/Rationale & Trade-Off                                                                                                                                                                           | Execution Model                         |
|:------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:----------------------------------------|
| **Browser Engine**      | Uses **Playwright (Java) 1.57.0** to execute JavaScript. This is required because Bing 2026 uses modular frameworks (ACF/Magazine) that inject results dynamically.                                        | **Headless Chromium** (Rendered DOM)    |
| **Resource Management** | Implements a **Warm Browser Singleton** to minimize startup latency, while using unique **BrowserContexts** per job to ensure 100% session isolation.                                                      | **Shared Browser / Isolated Contexts**  |
| **Result Extraction**   | Uses a **Functional Tracking URL Strategy**. Instead of brittle CSS classes, it counts links matching `bing.com/ck/a?!` (Organic) and `bing.com/aclk?` (Ads) that point to external domains.               | **Live DOM Locator Query**              |
| **Processing**          | Maximizes Cloud Run concurrency and instantly returns `200 OK` to Pub/Sub, freeing up the network thread.                                                                                                  | **@Async Processing** (Detached Thread) |
| **Trade-Off**           | Headless browsers are memory-intensive (~150MB-200MB per concurrent job). If the Cloud Run instance crashes before the job completes, the job is lost and the DB status remains `PROCESSING` indefinitely. |                                         |

---

## Error Handling Strategy

| Strategy                    | Implementation / Rationale               | Rationale                                                                                                                                                                    |
|:----------------------------|:-----------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Pub/Sub Acknowledgment**  | Synchronous `200 OK` Return              | The controller immediately returns `200 OK` (success) upon message reception and offloading to the thread pool, as it cannot wait for the final outcome of the detached job. |
| **In-App Retry**            | Spring `@Retryable` (on `BingScraper`)   | Implemented for **3 retries**. In Playwright mode, retries trigger a fresh `BrowserContext` to bypass potential page hangs or incomplete renders.                            |
| **Final Failure Status**    | DB status set to `FAILED`                | If all in-app retries fail, the `ScrapingService` updates the DB status to `FAILED`. The user must manually check the UI and trigger a re-upload of failed keywords.         |
| **Dead Letter Queue (DLQ)** | DLQ is **NOT** implemented for the beta. | **Trade-Off:** Accept that the end-user will handle the retry process for permanent failures in the beta.                                                                    |

### Future Enhancement

A **Dedicated Retry Topic (DLT)** will be implemented in v1 (using Pub/Sub subscription config) to store final failures for delayed, automated reprocessing, if user feedback demands automation.

## 🧠 Scraping Knowledge Base (v2026.1)

To maintain high stability against Bing's frequent UI updates, the scraper avoids fragile CSS selectors and instead utilizes **Functional Intent Detection** based on URL tracking patterns.

### 1. Functional Tracking Patterns
We categorize results by inspecting the target attribute of the `<a>` tag rather than the class of the parent container.

| Result Type  | Tracking Pattern                   | Pattern Description                                              |
|:-------------|:-----------------------------------|:-----------------------------------------------------------------|
| **Organic**  | `a[href*='bing.com/ck/a?!']`       | The standard "Click Key" redirect used for external web results. |
| **Ads**      | `a[href*='bing.com/aclk?']`        | The primary "Ad Click" redirect for sponsored listings.          |
| **Commerce** | `a[href*='/rebates/welcome?url=']` | Used for "Deals" and cashback-heavy shopping results.            |

### 2. The Destination Filter (Internal vs. External)
Bing utilizes the `ck/a?!` pattern for internal navigation as well. To ensure accurate organic counts, we apply a filter on the `u` (url) parameter within the tracking string.

* **KEEP (External):** If the `u` parameter decodes to an absolute external domain (e.g., `https://wikipedia.org`).
* **DISCARD (Internal):** If the `u` parameter points to internal Bing paths. These often appear as Base64 strings starting with:
    * `a1L2` (Decodes to `/`) — Indicates paths like `/search`, `/images`, or `/videos`.
    * `a1L3` (Decodes to internal service markers).

### 3. Execution Infrastructure
| Feature           | Strategy                 | Rationale                                                                                            |
|:------------------|:-------------------------|:-----------------------------------------------------------------------------------------------------|
| **Concurrency**   | Shared Browser Singleton | Saves ~2.5s of fixed cold-start latency per job by keeping Chromium "warm."                          |
| **Isolation**     | BrowserContext Per Job   | Ensures 100% session isolation. Cookies, cache, and local storage are wiped between scrapes.         |
| **Wait Strategy** | `NETWORKIDLE` + Selector | Waits for `#b_results` + Network Idle to ensure the JavaScript-heavy **ACF Grid** is fully hydrated. |
