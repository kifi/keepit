# ELIZA

# --- !Ups

alter table device
    add column is_dev boolean NOT NULL default 0;

insert into evolutions (name, description) values('251.sql', 'add is_dev column to device table');

# --- !Downs
