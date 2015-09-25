# SHOEBOX

# --- !Ups

ALTER TABLE bookmark DROP COLUMN url_id;
insert into evolutions (name, description) values('382.sql', 'dropped url_id from bookmark');

# --- !Downs
