# CURATOR

# --- !Ups

alter table raw_seed_item
    add column url varchar(3072) NOT NULL;

insert into evolutions (name, description) values('226.sql', 'add url column');

# --- !Downs
