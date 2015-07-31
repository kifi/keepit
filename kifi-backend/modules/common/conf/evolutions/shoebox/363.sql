# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ALTER COLUMN in_disjoint_lib tinyint(1) DEFAULT NULL;
ALTER TABLE bookmark DROP CONSTRAINT user_disjoint_primary_uri;

insert into evolutions(name, description) values('363.sql', 'drop the in_disjoin_lib column');

# --- !Downs
