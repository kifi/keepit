# SHOEBOX

# --- !Ups

-- MySQL:
-- CREATE TABLE phrase_sequence (id INT NOT NULL);
-- INSERT INTO phrase_sequence (SELECT MAX(id) FROM phrase);
-- ALTER TABLE phrase ADD seq INT NOT NULL DEFAULT id;
-- CREATE INDEX phrase_seq_index ON phrase(seq);
--insert into evolutions (name, description) values('117.sql', 'add sequence number to phrase');

-- H2:
CREATE SEQUENCE phrase_sequence;
ALTER TABLE phrase ADD seq INT NOT NULL DEFAULT 0;
CREATE INDEX phrase_seq_index ON phrase(seq);

insert into evolutions (name, description) values('117.sql', 'add sequence number to phrase');

# --- !Downs
