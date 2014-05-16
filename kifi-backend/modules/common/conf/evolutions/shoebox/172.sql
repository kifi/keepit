# ELIZA

# --- !Ups

CREATE UNIQUE INDEX user_thread_u_access_token ON user_thread (access_token);
-- mysql verion for prod:
-- CREATE UNIQUE INDEX user_thread_u_access_token ON user_thread (access_token(6));

insert into evolutions (name, description) values('172.sql', 'adding access token index to user_thread');

# --- !Downs
