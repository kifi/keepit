# SHOEBOX

# --- !Ups

ALTER TABLE library ADD COLUMN color varchar(7) DEFAULT NULL AFTER slug;

insert into evolutions (name, description) values('273.sql', 'add hex color to library (only 7 characters allowed i.e. #ffaa88)');

# --- !Downs
