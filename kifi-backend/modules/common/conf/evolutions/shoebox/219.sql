# CURATOR

# --- !Ups

ALTER TABLE uri_recommendation
  ADD COLUMN good boolean DEFAULT NULL;

ALTER TABLE uri_recommendation
  ADD COLUMN bad boolean DEFAULT NULL;

INSERT INTO evolutions (name, description) VALUES('216.sql', 'add user interaction columns to uri_recommendation table');

# --- !Downs
