# ELIZA

# --- !Ups

alter table message
  add column aux_data longtext NULL;

insert into evolutions (name, description) values('111.sql', 'adding aux_data (json field) to message');

# --- !Downs
