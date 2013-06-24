# --- !Ups

-- MySQL:
-- CREATE TABLE comment_sequence (id INT NOT NULL);
-- INSERT INTO comment_sequence VALUES (0);
-- ALTER TABLE comment ADD seq INT NOT NULL DEFAULT 0;
-- CREATE INDEX comment_i_seq ON comment(seq);
-- H2:
CREATE SEQUENCE comment_sequence;
---

ALTER TABLE comment ADD seq INT NOT NULL DEFAULT 0;
CREATE INDEX comment_i_seq ON comment(seq);

INSERT INTO evolutions (name, description) VALUES ('69.sql', 'add sequence number to comment');

# --- !Downs
