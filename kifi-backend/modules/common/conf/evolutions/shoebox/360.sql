# SHOEBOX


# --- !Ups

ALTER TABLE organization ADD site VARCHAR(2048) DEFAULT NULL AFTER normalized_handle;

insert into evolutions (name, description) values('360.sql', 'add site to organization');

# --- !Downs
