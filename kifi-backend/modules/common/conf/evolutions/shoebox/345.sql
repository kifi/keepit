# SHOEBOX

# --- !Ups

-- MySQL:
-- ALTER TABLE user_ip_addresses ADD seq BIGINT(20) NOT NULL DEFAULT 0;
-- CREATE TABLE user_ip_addresses_sequence (id BIGINT(20) NOT NULL);
-- INSERT user_ip_addresses_sequence SET id = 1;
-- UPDATE user_ip_addresses SET seq = (UNIX_TIMESTAMP(updated_at) * 1000 - 9223372036854775807);
-- H2:
CREATE SEQUENCE user_ip_addresses_sequence;
---

ALTER TABLE user_ip_addresses ADD seq BIGINT(20) NOT NULL DEFAULT 0;
CREATE INDEX user_ip_addresses_seq_index ON user_ip_addresses(seq);

insert into evolutions (name, description) values('345.sql', 'add sequence number to user_ip_addresses');

# --- !Downs
