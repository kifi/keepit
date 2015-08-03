# SHOEBOX

# --- !Ups

--- MySQL:
---   ALTER TABLE domain MODIFY hostname varchar(256) CHARACTER SET ascii collate ascii_general_ci;

ALTER TABLE domain ALTER COLUMN hostname varchar_ignorecase(256) NOT NULL;

insert into evolutions (name, description) values('366.sql', 'change charset for domain.hostname');

# --- !Downs
