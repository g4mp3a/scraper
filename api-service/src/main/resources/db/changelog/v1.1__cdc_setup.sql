-- liquibase formatted sql
-- changeset scraper:2 runOnChange:false splitStatements:true endDelimiter:;

-- Create the publication for the target outbox table, tracking only INSERTs.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'debezium_outbox_pub') THEN
        EXECUTE 'CREATE PUBLICATION debezium_outbox_pub FOR TABLE outbox_event WITH (publish = ''insert'')';
    END IF;
END
$$ LANGUAGE plpgsql;