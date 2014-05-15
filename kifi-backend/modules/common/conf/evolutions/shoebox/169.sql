# ELIZA

# --- !Ups

alter table non_user_thread
  add column created_by bigint(20) NOT NULL;

insert into evolutions (name, description) values('169.sql', 'adding created_by to non_user_thread');

# --- !Downs