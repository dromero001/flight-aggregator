-- The origin sort indexes (Q6-C, the user's decision at plan 5's end-of-plan review, I1): an origin
-- searched without a destination is read in each sort order behind an origin prefix, so a long-haul
-- origin, whose trips are the dearest, is not found by walking every cheaper trip first. Keyed by id
-- as V5's are (R11). Created on the partitioned parents, so every day partition and the DEFAULT one
-- inherit them.
CREATE INDEX itinerary_a_origin_price_idx ON itinerary_a (origin, price, id);
CREATE INDEX itinerary_a_origin_departure_idx ON itinerary_a (origin, departure, id);
CREATE INDEX itinerary_a_origin_duration_idx ON itinerary_a (origin, duration_seconds, id);
CREATE INDEX itinerary_b_origin_price_idx ON itinerary_b (origin, price, id);
CREATE INDEX itinerary_b_origin_departure_idx ON itinerary_b (origin, departure, id);
CREATE INDEX itinerary_b_origin_duration_idx ON itinerary_b (origin, duration_seconds, id);
