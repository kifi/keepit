# SHOEBOX

# --- !Ups

ALTER TABLE bookmark
  ADD COLUMN kept_at datetime DEFAULT NULL;

ALTER TABLE raw_keep
  ADD COLUMN created_date datetime DEFAULT NULL;

insert into evolutions (name, description) values('261.sql', 'adding kept_at column to bookmark table and created_date to the raw_keep table');

# --- !Downs
