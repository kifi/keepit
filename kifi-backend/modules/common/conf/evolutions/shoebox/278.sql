# SHOEBOX

# --- !Ups

ALTER TABLE library_membership DROP COLUMN visibility;

insert into evolutions (name, description) values('278.sql', 'drop visibility column from library membership');

# --- !Downs
