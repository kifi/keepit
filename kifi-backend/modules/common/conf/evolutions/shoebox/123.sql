# ELIZA

# --- !Ups

alter table user_thread
  drop column notification_last_seen;

insert into evolutions (name, description) values('123.sql', 'dropping user_thread.notification_last_seen');

# --- !Downs
