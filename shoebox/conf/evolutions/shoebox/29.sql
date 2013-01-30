# --- !Ups

alter TABLE browsing_history
  add column updates_count MEDIUMINT;

insert into evolutions (name, description) values('29.sql', 'adding updates_count column to browsing_history');

# --- !Downs
