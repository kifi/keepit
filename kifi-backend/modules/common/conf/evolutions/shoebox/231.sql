# SHOEBOX

# --- !Ups

ALTER TABLE library_invite
  ADD COLUMN email_address NULL;

ALTER TABLE library_invite
  ADD COLUMN auth_token NULL;


INSERT INTO evolutions (name, description) VALUES('231.sql', 'add email address and authentication token fields to library invites');

# --- !Downs
