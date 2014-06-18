# SHOEBOX

# --- !Ups

ALTER TABLE user ADD COLUMN if not exists primary_email varchar(512) NULL;

insert into evolutions (name, description) values('176.sql', 'add primary_email to user table');

# --- !Downs
