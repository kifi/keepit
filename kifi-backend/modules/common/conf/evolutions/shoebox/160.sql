# ELIZA

# --- !Ups

alter table message
  add column source varchar(36) NULL;

insert into evolutions (name, description) values('160.sql', 'adding source to message');

# --- !Downs
