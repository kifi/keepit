# ELIZA

# --- !Ups

ALTER TABLE message ADD non_user_sender TEXT;

insert into evolutions (name, description) values('155.sql', 'add non_user_sender to message');

# --- !Downs
