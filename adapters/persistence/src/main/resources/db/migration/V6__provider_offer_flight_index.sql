-- Re-price reads every provider's stored offer of one flight (§6.5) by its key: carrier, number,
-- origin and departure lead, and the few offers they leave are filtered on the destination.
CREATE INDEX provider_offer_flight_idx ON provider_offer (carrier, flight_number, origin, departure);
