# CURATOR

# --- !Ups

ALTER TABLE uri_recommendation
  ADD COLUMN vote boolean DEFAULT NULL;

INSERT INTO evolutions (name, description) VALUES('221.sql', 'add user interaction columns to uri_recommendation table');

# --- !Downs
