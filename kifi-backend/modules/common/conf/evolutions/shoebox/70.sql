# SHOEBOX

# --- !Ups

alter TABLE normalized_uri
    add column screenshot_updated_at datetime;

insert into evolutions (name, description) values('70.sql', 'add screenshot_updated_at to normalized_uri');

# --- !Downs
