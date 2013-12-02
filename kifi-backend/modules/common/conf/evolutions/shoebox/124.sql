# ELIZA

# --- !Ups

alter table user_thread 
  add column last_active datetime NULL;

alter table user_thread
  add column started bool NOT NULL DEFAULT 0; 

insert into evolutions (name, description) values('124.sql', 'adding last_active and started columns to user thread');

# --- !Downs
