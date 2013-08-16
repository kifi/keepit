# ELIZA

# --- !Ups
  
alter table message_thread 
  add column page_title text NULL; 

insert into evolutions (name, description) values('86.sql', 'adding page title column to message_thread');

# --- !Downs
