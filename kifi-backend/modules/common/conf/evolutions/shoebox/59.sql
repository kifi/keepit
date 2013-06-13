# --- !Ups

ALTER TABLE user_to_domain
  ADD COLUMN value varchar(256) /*AFTER kind*/;

insert into evolutions (name, description) values('59.sql', 'add value to user_to_domain');

# --- !Downs
