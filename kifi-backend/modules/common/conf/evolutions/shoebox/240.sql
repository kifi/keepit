# SHOEBOX

# --- !Ups

ALTER TABLE library_invite
  ADD COLUMN passcode varchar(20) NOT NULL;
ALTER TABLE library_invite
  MODIFY COLUMN auth_token varchar(40) NOT NULL;

INSERT INTO evolutions (name, description) VALUES('240.sql', 'adding unique passcodes to library invites');

# --- !Downs
