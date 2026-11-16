-- The destination sort indexes (Q6-C, the user's decision at the re-review of plan 5's closing
-- round 1, N1): a destination searched without an origin is read in each sort order behind a
-- destination prefix, so trips to a long-haul airport, the dearest, are not found by walking every
-- cheaper trip first. Keyed by id as V5's and V8's are (R11). Created on the partitioned parents,
-- so every day partition and the DEFAULT one inherit them.
CREATE INDEX itinerary_a_destination_price_idx ON itinerary_a (destination, price, id);
CREATE INDEX itinerary_a_destination_departure_idx ON itinerary_a (destination, departure, id);
CREATE INDEX itinerary_a_destination_duration_idx ON itinerary_a (destination, duration_seconds, id);
CREATE INDEX itinerary_b_destination_price_idx ON itinerary_b (destination, price, id);
CREATE INDEX itinerary_b_destination_departure_idx ON itinerary_b (destination, departure, id);
CREATE INDEX itinerary_b_destination_duration_idx ON itinerary_b (destination, duration_seconds, id);
