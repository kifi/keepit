# SHOEBOX

# --- !Ups

ALTER TABLE bookmark DROP COLUMN in_disjoint_lib;

insert into evolutions(name, description) values('363.sql', 'drop the in_disjoin_lib column');

# --- !Downs
