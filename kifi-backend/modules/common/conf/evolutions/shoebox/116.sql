# SHOEBOX

# --- !Ups

alter table user_picture
  add column attributes longtext NULL;

insert into evolutions (name, description) values('116.sql', 'adding attributes (json field) to user_picture');

# --- !Downs
