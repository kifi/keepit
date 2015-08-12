# SHOEBOX

# --- !Ups

ALTER TABLE email_address DROP COLUMN `primary_email`;

insert into evolutions (name, description) values('373.sql', 'drop column email_address.primary_email');

# --- !Downs
