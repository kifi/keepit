# --- !Ups

-- MySQL:
-- CREATE TABLE normalized_uri_sequence (id INT NOT NULL);
-- INSERT INTO normalized_uri_sequence VALUES (0);
-- H2:
CREATE SEQUENCE normalized_uri_sequence;
---

ALTER TABLE normalized_uri ADD seq INT;
CREATE INDEX normalized_uri_seq_index ON normalized_uri(seq);

insert into evolutions (name, description) values('44.sql', 'add sequence number to normalized uri');

# --- !Downs
