-- The last complete download of each provider, in the page order it was stored. Times are an
-- instant plus the provider's offset in seconds, so the round trip is exact (R3).
CREATE TABLE provider_offer (
    provider         text          NOT NULL,
    position         int           NOT NULL,
    carrier          char(2)       NOT NULL,
    flight_number    int           NOT NULL,
    origin           char(3)       NOT NULL,
    destination      char(3)       NOT NULL,
    departure        timestamptz   NOT NULL,
    departure_offset int           NOT NULL,
    arrival          timestamptz   NOT NULL,
    arrival_offset   int           NOT NULL,
    price            numeric(11,2) NOT NULL,
    quoted_at        timestamptz   NOT NULL,
    PRIMARY KEY (provider, position)
);
