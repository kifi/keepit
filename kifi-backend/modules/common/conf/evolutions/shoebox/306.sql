# SHOEBOX

# --- !Ups

alter table raw_keep add column hashtags text default null;

insert into evolutions (name, description) values('306.sql', 'add column hashtags to raw_keep table');

# --- !Downs
