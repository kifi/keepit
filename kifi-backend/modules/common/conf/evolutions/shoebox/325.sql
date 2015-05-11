# SHOEBOX

# --- !Ups

ALTER TABLE library_membership ADD COLUMN subscribed_to_updates boolean DEFAULT false;

insert into evolutions (name, description) values('325.sql', 'add subscribed_to_updates column to library_membership table');

# --- !Downs
