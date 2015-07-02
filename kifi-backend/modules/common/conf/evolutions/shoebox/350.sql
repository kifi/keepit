# SHOEBOX

# --- !Ups

ALTER TABLE `library_membership` ADD COLUMN starred varchar(16) DEFAULT NULL;

insert into evolutions (name, description) values('350.sql', 'add starred to library_membership');

# --- !Downs
