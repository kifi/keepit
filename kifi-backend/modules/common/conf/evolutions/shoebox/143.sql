# SHOEBOX

# --- !Ups

CREATE SEQUENCE email_address_sequence;
ALTER TABLE email_address add seq Int NOT NULL DEFAULT 0;
CREATE INDEX email_address_i_seq ON email_address(seq);

-- MySQL:
-- CREATE TABLE email_address_sequence (id INT NOT NULL);
-- INSERT INTO email_address_sequence VALUES (0);
-- H2:

INSERT INTO evolutions (name, description) VALUES ('143.sql', 'add sequence number to email_address');

# --- !Downs
