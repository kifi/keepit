# SHOEBOX

# --- !Ups

alter TABLE raw_keep
  add column tag_ids TEXT NULL;

insert into evolutions (name, description) values('166.sql', 'add tag_ids to raw_keep');

# --- !Downs
