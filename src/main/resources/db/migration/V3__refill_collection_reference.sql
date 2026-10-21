-- Reference issued when a refill is approved and quoted back at hand-over.
ALTER TABLE refill_request ADD COLUMN collection_reference VARCHAR(12);
