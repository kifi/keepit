# --- !Ups

alter table electronic_mail
  add column from_name varchar(256);

insert into evolutions (name, description) values('17.sql', 'adding from_name to emails');

# --- !Downs
