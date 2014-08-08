# CURATOR

# --- !Ups

ALTER TABLE curator_keep_info
  ADD COLUMN discoverable boolean NOT NULL DEFAULT false;

INSERT INTO evolutions (name, description) VALUES('223.sql', 'add discoverable columns to curator_keep_info table');

# --- !Downs
