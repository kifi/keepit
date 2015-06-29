# SHOEBOX

# --- !Ups

ALTER TABLE `library_membership` ADD COLUMN starred varchar(16) default 0;

insert into evolutions (name, description) values('349.sql', 'add starred to library_membership');

# --- !Downs
