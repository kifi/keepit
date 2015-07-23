# SHOEBOX


# --- !Ups

ALTER TABLE organization ADD url VARCHAR(2048) DEFAULT NULL;

insert into evolutions (name, description) values('360.sql', 'add url to organization');


# --- !Downs
