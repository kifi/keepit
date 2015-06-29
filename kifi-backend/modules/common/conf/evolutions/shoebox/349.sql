# SHOEBOX

# --- !Ups

ALTER TABLE `library_membership` ADD COLUMN starred TINYINT default 0;

insert into evolutions (name, description) values('347.sql', 'add starred to library_membership');

# --- !Downs
