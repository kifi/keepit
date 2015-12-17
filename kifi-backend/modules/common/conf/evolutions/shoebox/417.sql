# ELIZA

# --- !Ups

-- MySQL:
-- CREATE TABLE message_sequence (id INT NOT NULL);
-- INSERT INTO message_sequence VALUES (0);
-- H2:
CREATE SEQUENCE message_sequence;
---

ALTER TABLE message add seq bigint(20) NOT NULL DEFAULT 0;
CREATE INDEX message_i_seq ON message(seq);

INSERT INTO evolutions (name, description) VALUES ('417.sql', 'add sequence number to message');
