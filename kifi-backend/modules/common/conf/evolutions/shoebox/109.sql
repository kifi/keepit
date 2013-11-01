#SHOEBOX

# --- !Ups

ALTER TABLE email_address
  ADD COLUMN verification_code VARCHAR(32),
  ADD INDEX email_address_i_verification_code ON (verification_code);

# --- !Downs
