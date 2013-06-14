# --- !Ups

-- MySQL:
-- CREATE TABLE collection_sequence (id INT NOT NULL);
-- INSERT INTO collection_sequence VALUES (0);
-- ALTER TABLE collection ADD seq INT NOT NULL DEFAULT 0;
-- CREATE INDEX collection_i_seq ON collection(seq);
-- H2:
CREATE SEQUENCE collection_sequence;
---

ALTER TABLE collection ADD seq INT NOT NULL DEFAULT 0;
CREATE INDEX collection_i_seq ON collection(seq);

INSERT INTO evolutions (name, description) VALUES ('61.sql', 'add sequence number to collection');

# --- !Downs
