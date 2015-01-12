# CURATOR

# --- !Ups

ALTER TABLE bookmark MODIFY kept_at datetime NOT NULL;

INSERT INTO evolutions (name, description) VALUES('281.sql', 'keep kept_at not null');

# --- !Downs
