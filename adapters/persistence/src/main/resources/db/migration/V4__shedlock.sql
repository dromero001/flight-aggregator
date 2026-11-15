-- The ingestion job's lock (§6.2): ShedLock's table for PostgreSQL, as its README (7.10.1) gives
-- it. The lock provider uses the database's clock, which writes these timestamps in UTC.
CREATE TABLE shedlock(name VARCHAR(64) NOT NULL, lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL, locked_by VARCHAR(255) NOT NULL, PRIMARY KEY (name));
