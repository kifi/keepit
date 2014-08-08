# CURATOR

# --- !Ups
  
ALTER TABLE raw_seed_item_sequence DROP discoverable;

ALTER TABLE raw_seed_item
  ADD COLUMN discoverable boolean NOT NULL DEFAULT false;

INSERT INTO evolutions (name, description) VALUES('224.sql', 'drop wrong column in raw_seed_item_sequence, add discoverable in raw_seed_item');

# --- !Downs