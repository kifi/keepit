# SHOEBOX

# --- !Ups

ALTER TABLE library_invite
  ADD COLUMN email_address varchar(50) NULL;

ALTER TABLE library_invite
  ADD COLUMN auth_token varchar(50) NULL;


INSERT INTO evolutions (name, description) VALUES('231.sql', 'add email address and authentication token fields to library invites');

# --- !Downs
