# SHOEBOX

# --- !Ups

alter TABLE normalized_uri
add column redirect bigint(20) NULL;

alter TABLE normalized_uri
add column redirect_time datetime NULL;

insert into evolutions (name, description) values('82.sql', 'add redirect info to normalized_uri');

# --- !Downs
