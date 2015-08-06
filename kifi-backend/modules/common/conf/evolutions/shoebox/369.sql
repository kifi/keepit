# SHOEBOX

# --- !Ups

ALTER TABLE email_address ADD COLUMN `primary` boolean NULL AFTER state;

insert into evolutions (name, description) values('369.sql', 'add column email_address.primary');

# --- !Downs
