# SHOEBOX

# --- !Ups

-- MySQL:
-- ALTER TABLE user_ip_addresses ADD seq INT NOT NULL DEFAULT 0;
-- CREATE TABLE ip_address_sequence (id INT NOT NULL);
-- INSERT INTO ip_address_sequence VALUES (0);
-- H2:
CREATE SEQUENCE ip_address_sequence;
---

ALTER TABLE user_ip_addresses ADD seq INT NOT NULL DEFAULT 0;
CREATE INDEX ip_address_seq_index ON user_ip_addresses(seq);

insert into evolutions (name, description) values('344.sql', 'add sequence number to user_ip_addresses');

# --- !Downs
