# SHOEBOX

# --- !Ups

ALTER TABLE library_invite
  ADD COLUMN email_address varchar(30) NULL;

INSERT INTO evolutions (name, description) VALUES('226.sql', 'add email address field to library invites');

# --- !Downs
