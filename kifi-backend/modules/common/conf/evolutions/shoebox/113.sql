# SHOEBOX

# --- !Ups

-- MySQL:
-- CREATE TABLE user_sequence (id INT NOT NULL);
-- INSERT INTO user_sequence VALUES (0);
-- H2:
CREATE SEQUENCE user_sequence;
---

ALTER TABLE user add seq Int NOT NULL DEFAULT 0;
CREATE INDEX user_i_seq ON user(seq);

INSERT INTO evolutions (name, description) VALUES ('113.sql', 'add sequence number to user');


# --- !Downs
