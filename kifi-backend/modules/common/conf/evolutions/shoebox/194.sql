# ABOOK

# --- !Ups

-- MySQL:
-- CREATE TABLE econtact_sequence (id bigint(20) NOT NULL);
-- INSERT INTO econtact_sequence VALUES (0);
-- H2:
CREATE SEQUENCE econtact_sequence;

ALTER TABLE econtact ADD COLUMN seq bigint(20) NOT NULL;
CREATE INDEX econtact_i_seq on econtact(seq);

insert into evolutions (name, description) values('194.sql', 'add sequence number to econtact');

# --- !Downs
