# CURATOR

# --- !Ups

alter table raw_seed_item
    add column url text NOT NULL DEFAULT '';

insert into evolutions (name, description) values('226.sql', 'add url column');

# --- !Downs
