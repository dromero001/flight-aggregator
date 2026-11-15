-- The itineraries search reads, double-buffered (§6.5): two identical slots, of which
-- inventory_publication names the active one. Each run loads the other slot and switches to it in
-- one transaction. A slot is partitioned by departure, with day partitions at UTC midnights (R12)
-- created by each run, and a DEFAULT partition for any departure without one (§4.3). The slots are
-- written out in full rather than with LIKE, which would give itinerary_b the not-null constraint
-- names of itinerary_a, and its errors with them.
CREATE TABLE itinerary_a (
    id               uuid          NOT NULL,
    departure        timestamptz   NOT NULL,
    departure_offset int           NOT NULL,
    arrival          timestamptz   NOT NULL,
    arrival_offset   int           NOT NULL,
    origin           char(3)       NOT NULL,
    destination      char(3)       NOT NULL,
    duration_seconds int           NOT NULL,
    stops            smallint      NOT NULL,
    single_carrier   char(2)       NULL,
    price            numeric(11,2) NOT NULL,
    quoted_at        timestamptz   NOT NULL,
    legs             jsonb         NOT NULL,
    PRIMARY KEY (id, departure)
) PARTITION BY RANGE (departure);

CREATE TABLE itinerary_b (
    id               uuid          NOT NULL,
    departure        timestamptz   NOT NULL,
    departure_offset int           NOT NULL,
    arrival          timestamptz   NOT NULL,
    arrival_offset   int           NOT NULL,
    origin           char(3)       NOT NULL,
    destination      char(3)       NOT NULL,
    duration_seconds int           NOT NULL,
    stops            smallint      NOT NULL,
    single_carrier   char(2)       NULL,
    price            numeric(11,2) NOT NULL,
    quoted_at        timestamptz   NOT NULL,
    legs             jsonb         NOT NULL,
    PRIMARY KEY (id, departure)
) PARTITION BY RANGE (departure);

CREATE TABLE itinerary_a_default PARTITION OF itinerary_a DEFAULT;
CREATE TABLE itinerary_b_default PARTITION OF itinerary_b DEFAULT;

-- One row: the slot search reads and the generation it holds. Generation 0 is the empty inventory.
CREATE TABLE inventory_publication (
    id           smallint    PRIMARY KEY CHECK (id = 1),
    active_slot  char(1)     NOT NULL CHECK (active_slot IN ('a', 'b')),
    generation   bigint      NOT NULL,
    published_at timestamptz
);

INSERT INTO inventory_publication (id, active_slot, generation, published_at) VALUES (1, 'a', 0, NULL);
