-- The keyset sort indexes (Q6): for each slot, the three sort orders (price, departure, duration),
-- each keyed by id so a page ends at a unique position (R11), alone and behind an origin and
-- destination prefix. They are created on the partitioned parents, so every present and future day
-- partition, and the DEFAULT partition, inherits them.
CREATE INDEX itinerary_a_price_idx ON itinerary_a (price, id);
CREATE INDEX itinerary_a_route_price_idx ON itinerary_a (origin, destination, price, id);
CREATE INDEX itinerary_a_departure_idx ON itinerary_a (departure, id);
CREATE INDEX itinerary_a_route_departure_idx ON itinerary_a (origin, destination, departure, id);
CREATE INDEX itinerary_a_duration_idx ON itinerary_a (duration_seconds, id);
CREATE INDEX itinerary_a_route_duration_idx ON itinerary_a (origin, destination, duration_seconds, id);
CREATE INDEX itinerary_b_price_idx ON itinerary_b (price, id);
CREATE INDEX itinerary_b_route_price_idx ON itinerary_b (origin, destination, price, id);
CREATE INDEX itinerary_b_departure_idx ON itinerary_b (departure, id);
CREATE INDEX itinerary_b_route_departure_idx ON itinerary_b (origin, destination, departure, id);
CREATE INDEX itinerary_b_duration_idx ON itinerary_b (duration_seconds, id);
CREATE INDEX itinerary_b_route_duration_idx ON itinerary_b (origin, destination, duration_seconds, id);
