CREATE TABLE IF NOT EXISTS disaster_reports (
    report_id       VARCHAR(100) PRIMARY KEY,
    report_payload  JSONB NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS event_outbox (
    event_id        UUID PRIMARY KEY,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    event_payload   JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_event_outbox_pending
    ON event_outbox (created_at)
    WHERE published_at IS NULL;