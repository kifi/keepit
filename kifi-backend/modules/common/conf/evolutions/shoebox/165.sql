# SHOEBOX

# --- !Ups

alter TABLE raw_keep
  add column tag_ids varchar(64) NULL;

insert into evolutions (name, description) values('165.sql', 'add tag_ids to raw_keep');

# --- !Downs