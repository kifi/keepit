# SHOEBOX

# --- !Ups

-- MySQL:
-- CREATE TABLE changed_uri_sequence (id INT NOT NULL);
-- INSERT INTO changed_uri_sequence VALUES (0);
-- H2:
CREATE SEQUENCE changed_uri_sequence;
---
CREATE INDEX changed_uri_seq_index ON changed_uri(seq);

insert into evolutions (name, description) values('83.sql', 'add sequence number to changed uri');

# --- !Downs
