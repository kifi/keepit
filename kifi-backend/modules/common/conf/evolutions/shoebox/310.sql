# SHOEBOX

# --- !Ups

alter table library
    add column keep_count int(11) NOT NULL DEFAULT '0';

insert into evolutions (name, description) values('310.sql', 'add keep count to library table');

# --- !Downs
