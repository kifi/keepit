# --- !Ups
  
alter table message_thread 
  add column nUrl text NULL; 

insert into evolutions (name, description) values('2.sql', 'adding normalized url column to message_thread');

# --- !Downs
