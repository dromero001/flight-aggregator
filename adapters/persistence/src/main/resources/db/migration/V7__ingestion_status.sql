-- Each provider's status as the last ingestion run left it (Q7-B), for the provider-status screen
-- (§6.8): one row per provider, written at the end of every run, so that any instance reads the
-- same status and a restart does not lose it. A provider without a failure stored a fresh download;
-- one with a failure kept its last good offers.
CREATE TABLE ingestion_status (
    provider         text        NOT NULL PRIMARY KEY,
    failure          text        NULL,
    offers_used      int         NOT NULL,
    newest_quoted_at timestamptz NULL,
    finished_at      timestamptz NOT NULL
);

-- The records that run discarded from the provider's download, by reason; a reason with none has no
-- row. They go with their provider's row.
CREATE TABLE ingestion_status_discarded (
    provider text NOT NULL REFERENCES ingestion_status (provider) ON DELETE CASCADE,
    reason   text NOT NULL,
    records  int  NOT NULL,
    PRIMARY KEY (provider, reason)
);
