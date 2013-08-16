# SHOEBOX

# --- !Ups

alter TABLE slider_history
  add column updates_count MEDIUMINT;

insert into evolutions (name, description) values('74.sql', 'adding updates_count column to slider_history');

# --- !Downs
