# --- !Ups

alter table bookmark 
  add column source varchar(256) NOT NULL; 

insert into evolutions (name, description) values('4.sql', 'adding bookmark source');

# --- !Downs
