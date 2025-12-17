-- liquibase formatted sql
-- changeset scraper:1 runOnChange:false splitStatements:true endDelimiter:;

-- 1. Create the custom ENUM type for job status
CREATE TYPE search_status AS ENUM ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED');

-- 2. APP_USER Table (Stores user metadata linked to Firebase UID)
CREATE TABLE app_user (
    firebase_uid VARCHAR(128) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW() AT TIME ZONE 'UTC'
);

-- 3. KEYWORD_SEARCH Table (Stores the job details and results)
CREATE TABLE keyword_search (
    id BIGSERIAL PRIMARY KEY,
    app_user_id VARCHAR(128) NOT NULL,
    keyword VARCHAR(255) NOT NULL,
    status search_status NOT NULL DEFAULT 'PENDING',
    
    total_links INTEGER,
    total_ads INTEGER,
    full_html TEXT,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW() AT TIME ZONE 'UTC',
    completed_at TIMESTAMP WITH TIME ZONE,
    
    -- Foreign Key Constraint
    CONSTRAINT fk_user
        FOREIGN KEY (app_user_id)
        REFERENCES app_user(firebase_uid)
        ON DELETE CASCADE
);
CREATE INDEX idx_keyword_search_user ON keyword_search (app_user_id, created_at DESC);

-- 4. OUTBOX_EVENT Table (Transactional Outbox Pattern)
CREATE TABLE outbox_event (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW() AT TIME ZONE 'UTC',
    processed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_outbox_event_unprocessed ON outbox_event (id) WHERE processed_at IS NULL;


