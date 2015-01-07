# CURATOR

# --- !Ups

ALTER TABLE library_recommendation ADD COLUMN vote BOOLEAN NULL;

INSERT INTO evolutions (name, description) VALUES('279.sql', 'add column to library_recommendation (vote)');

# --- !Downs
