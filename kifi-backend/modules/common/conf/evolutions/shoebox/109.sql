#SHOEBOX

# --- !Ups

ALTER TABLE email_address
  ADD COLUMN verification_code VARCHAR(32);

CREATE INDEX email_address_i_verification_code ON email_address(verification_code);

# --- !Downs
