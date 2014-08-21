# SHOEBOX

# --- !Ups

ALTER TABLE library
  ADD COLUMN universal_link varchar(50) NULL;


INSERT INTO evolutions (name, description) VALUES('233.sql', 'add universal link to library table');

# --- !Downs