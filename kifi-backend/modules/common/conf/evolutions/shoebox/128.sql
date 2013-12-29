# SHOEBOX

# --- !Ups

DROP TABLE follow;

insert into evolutions (name, description) values('128.sql', 'dropping the follow table');

# --- !Downs
