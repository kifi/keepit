# SHOEBOX

# --- !Ups

ALTER TABLE bookmark DROP COLUMN kifi_installation;
insert into evolutions (name, description) values('380.sql', 'drop kifi_installation from bookmark');

# --- !Downs
