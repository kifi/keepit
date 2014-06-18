# SHOEBOX

# --- !Ups

ALTER TABLE user ADD COLUMN if not exists primary_email varchar(512) NULL;

insert into evolutions (name, description) values('175.sql', 'add primary_email to user table');

# --- !Downs
