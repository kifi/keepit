# SHOEBOX

# --- !Ups

ALTER TABLE persona add COLUMN display_name_plural varchar(32) NOT NULL AFTER display_name;

insert into evolutions (name, description) values('292.sql', 'adding display_name (plural form)');

# --- !Downs
