# ELIZA

# --- !Ups
  
alter table user_thread 
  add column notification_emailed bool NOT NULL DEFAULT FALSE; 

insert into evolutions (name, description) values('89.sql', 'adding notification_emailed column to user_thread');

# --- !Downs
