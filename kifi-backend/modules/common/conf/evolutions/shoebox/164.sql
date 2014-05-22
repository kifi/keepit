# ELIZA

# --- !Ups

CREATE UNIQUE INDEX non_user_thread_u_access_token ON non_user_thread (access_token);


insert into evolutions (name, description) values('164.sql', 'adding access token index to non_user_thread');

# --- !Downs
