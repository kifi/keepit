# SHOEBOX

# --- !Ups

ALTER TABLE bookmark
  ADD COLUMN visibility varchar(32) NULL;


INSERT INTO evolutions (name, description) VALUES('236.sql', 'add visibility to bookmark table, denormalization of library visibility');

# --- !Downs
