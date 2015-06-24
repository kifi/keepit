# SHOEBOX

# --- !Ups

-- MySQL:
-- CREATE TABLE organization_sequence (id BIGINT(20) NOT NULL);
-- INSERT organization_sequence SET id = 0;
-- CREATE TABLE organization_membership_sequence (id BIGINT(20) NOT NULL);
-- INSERT organization_membership_sequence SET id = 0;
-- H2:
CREATE SEQUENCE organization_sequence;

CREATE INDEX organization_seq_index ON organization(seq);

CREATE SEQUENCE organization_membership_sequence;

CREATE INDEX organization_membership_seq_index ON organization_membership(seq);

insert into evolutions (name, description) values('346.sql', 'add organization_sequence and organization_membership_sequence');

# --- !Downs
