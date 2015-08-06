# SHOEBOX

# --- !Ups

ALTER TABLE keep_to_library ADD COLUMN added_at DATETIME DEFAULT NULL;

insert into evolutions (name, description) values('369.sql', 'add added_at and visibility fields to keep_to_library');

# --- !Downs
