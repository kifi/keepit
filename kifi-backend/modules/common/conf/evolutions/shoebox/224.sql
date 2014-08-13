# CURATOR

# --- !Ups

ALTER TABLE uri_recommendation
  ADD COLUMN last_pushed_at datetime NULL;

INSERT INTO evolutions (name, description) VALUES('224.sql', 'add last_pushed_at to curator.uri_recommendation table');

# --- !Downs