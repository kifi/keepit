#SHOEBOX

# --- !Ups

ALTER TABLE email_address ADD COLUMN verification_code VARCHAR(32);

# --- !Downs
