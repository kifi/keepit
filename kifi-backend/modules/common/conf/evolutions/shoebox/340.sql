# Shoebox

# --- !Ups

drop table username_alias;

insert into evolutions(name, description) values('340.sql', 'drop username_alias table');

# --- !Downs
