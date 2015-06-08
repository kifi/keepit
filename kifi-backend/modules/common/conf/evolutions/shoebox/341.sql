# Shoebox

# --- !Ups

drop table username_alias;

insert into evolutions(name, description) values('341.sql', 'drop username_alias table');

# --- !Downs
