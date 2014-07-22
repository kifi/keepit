# SHOEBOX

# --- !Ups

ALTER TABLE library DROP COLUMN external_id;

insert into evolutions (name, description) values('202.sql', 'droping external_id');

# --- !Downs
