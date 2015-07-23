# SHOEBOX


# --- !Ups

ALTER TABLE organization ADD site VARCHAR(2048) DEFAULT NULL;

insert into evolutions (name, description) values('360.sql', 'add site to organization');

# --- !Downs
