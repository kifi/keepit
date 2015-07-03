# SHOEBOX

# --- !Ups

ALTER TABLE `library_membership` ADD COLUMN priority BIGINT(20)  NOT NULL;

insert into evolutions (name, description) values('350.sql', 'add priority to library_membership');

# --- !Downs
