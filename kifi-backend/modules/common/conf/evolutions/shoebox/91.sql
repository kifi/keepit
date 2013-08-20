# ELIZA

# --- !Ups
  
alter table user_thread 
  add column replyable bool NOT NULL DEFAULT TRUE; 

insert into evolutions (name, description) values('91.sql', 'adding replyable (from message_thread) denormalization to user_thread');

# --- !Downs
