# --- !Ups

-- MySQL:
-- ALTER TABLE bookmark ADD seq INT NOT NULL DEFAULT 0;
-- CREATE TABLE bookmark_sequence (id INT NOT NULL);
-- INSERT INTO bookmark_sequence VALUES (0);
-- H2:
CREATE SEQUENCE bookmark_sequence;
---

ALTER TABLE bookmark ADD seq INT NOT NULL DEFAULT 0;
CREATE INDEX bookmark_seq_index ON bookmark(seq);

insert into evolutions (name, description) values('45.sql', 'add sequence number to bookmark');

# --- !Downs
