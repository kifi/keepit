# SHOEBOX

# --- !Ups

ALTER TABLE domain ADD hash VARCHAR(26) DEFAULT NULL;

CREATE UNIQUE INDEX domain_hash_index ON DOMAIN (hash);

DROP INDEX domain_hostname_index;

insert into evolutions (name, description) values('361.sql', 'add hash, drop hostname index from `domain`');

# --- !Downs
