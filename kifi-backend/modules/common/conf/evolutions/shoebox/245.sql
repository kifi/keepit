# SHOEBOX

# --- !Ups

alter table library
    modify column description text default null;

insert into evolutions (name, description) values('245.sql', 'description is text');

# --- !Downs
