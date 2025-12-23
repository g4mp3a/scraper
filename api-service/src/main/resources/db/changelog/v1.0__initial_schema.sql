-- liquibase formatted sql
-- changeset scraper:1 runOnChange:false failOnError:false

---- 1. Create the custom ENUM type using a custom delimiter to handle the DO block
--DO $$
--BEGIN
--    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'search_status') THEN
--        CREATE TYPE search_status AS ENUM ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED');
--    END IF;
--END $$;
--$$
CREATE TYPE search_status AS ENUM ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED');

-- changeset scraper:2 runOnChange:false splitStatements:true endDelimiter:;
-- 2. APP_USER Table
CREATE TABLE IF NOT EXISTS app_user (
    firebase_uid VARCHAR(128) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. KEYWORD_SEARCH Table
CREATE TABLE IF NOT EXISTS keyword_search (
    id BIGSERIAL PRIMARY KEY,
    app_user_id VARCHAR(128) NOT NULL,
    keyword VARCHAR(255) NOT NULL,
    status search_status NOT NULL DEFAULT 'PENDING',

    total_links INTEGER,
    total_ads INTEGER,
    full_html TEXT,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_user
        FOREIGN KEY (app_user_id)
        REFERENCES app_user(firebase_uid)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_keyword_search_user ON keyword_search (app_user_id, created_at DESC);

-- 4. OUTBOX_EVENT Table
CREATE TABLE IF NOT EXISTS outbox_event (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_outbox_event_unprocessed ON outbox_event (id) WHERE processed_at IS NULL;
