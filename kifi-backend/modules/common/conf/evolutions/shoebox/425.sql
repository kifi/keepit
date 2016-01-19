# SHOEBOX

# --- !Ups

alter TABLE twitter_sync_state add column sync_target varchar(32) NULL;

insert into evolutions(name, description) values('425.sql', 'add sync_target to twitter_sync_state');

# --- !Downs
