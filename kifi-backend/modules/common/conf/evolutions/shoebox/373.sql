# SHOEBOX

# --- !Ups

ALTER TABLE `user` DROP COLUMN `primary_email`;

insert into evolutions (name, description) values('373.sql', 'drop column user.primary_email');

# --- !Downs
