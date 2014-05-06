# ELIZA

# --- !Ups

ALTER TABLE user_thread ADD COLUMN access_token char(32);
ALTER TABLE non_user_thread ADD COLUMN access_token char(32);

insert into evolutions (name, description) values('162.sql', 'add access_token columns to user_thread and non_user_thread');

# --- !Downs
