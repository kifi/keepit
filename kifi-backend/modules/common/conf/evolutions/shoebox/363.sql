# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ALTER COLUMN in_disjoint_lib tinyint(1) DEFAULT NULL;
-- DROP INDEX `user_disjoint_primary_uri` ON bookmark;

insert into evolutions(name, description) values('363.sql', 'drop the in_disjoin_lib column');

# --- !Downs
