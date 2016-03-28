# SHOEBOX

# --- !Ups

alter table bookmark add column initial_event text default null;

insert into evolutions(name, description) values('435.sql', 'add initial_event to bookmark');

# --- !Downs
