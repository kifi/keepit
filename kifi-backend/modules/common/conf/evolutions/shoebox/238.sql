# SHOEBOX

# --- !Ups

ALTER TABLE library_invite
  ALTER COLUMN user_id bigint(20) DEFAULT NULL;

INSERT INTO evolutions (name, description) VALUES('238.sql', 'allow libraries to invite emailaddress OR userId');

# --- !Downs
