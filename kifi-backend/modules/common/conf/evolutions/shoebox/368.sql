# SHOEBOX

# --- !Ups

ALTER TABLE organization_avatar MODIFY COLUMN path VARCHAR(256) NOT NULL;

insert into evolutions (name, description) values('368.sql', 'make avatar image paths longer');

# --- !Downs
