# ELIZA

# --- !Ups

CREATE UNIQUE INDEX non_user_thread_u_access_token ON non_user_thread (access_token);
-- mysql verion for prod:
-- CREATE UNIQUE INDEX non_user_thread_u_access_token ON non_user_thread (access_token(6));

insert into evolutions (name, description) values('164.sql', 'adding access token index to non_user_thread');

# --- !Downs
