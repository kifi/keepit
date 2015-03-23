# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ADD COLUMN note longtext NULL;

INSERT INTO evolutions (name, description) VALUES('307.sql', 'add column note to keep');

# --- !Downs
