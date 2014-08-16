# SHOEBOX

# --- !Ups

ALTER TABLE library_invite
  ADD COLUMN email_address NULL;

INSERT INTO evolutions (name, description) VALUES('229.sql', 'add email address field to library invites');

# --- !Downs