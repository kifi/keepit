# SHOEBOX

# --- !Ups

ALTER TABLE library_invite
  MODIFY COLUMN user_id bigint(20) DEFAULT NULL;

INSERT INTO evolutions (name, description) VALUES('239.sql', 'make user_id nullable to allow libraries to invite emailaddress OR userId');

# --- !Downs
