# SHOEBOX

# --- !Ups

alter table library_suggested_search add column kind varchar(32) default 'auto';

insert into evolutions (name, description) values('335.sql', 'add column kind to library_suggested_search');

# --- !Downs
