# SHOEBOX

# --- !Ups

ALTER TABLE keep_image add COLUMN kind varchar(32) NOT NULL DEFAULT 'scale';

insert into evolutions (name, description) values('293.sql', 'add keep_image(kind)');

# --- !Downs
