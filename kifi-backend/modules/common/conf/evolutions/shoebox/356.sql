# SHOEBOX

# --- !Ups

-- MySQL:
-- CREATE TABLE organization_membership_candidate_sequence (id BIGINT(20) NOT NULL);
-- INSERT organization_membership_candidate_sequence SET id = 0;
-- ALTER TABLE organization_membership_candidate ADD seq BIGINT(20) NOT NULL);
-- H2:

ALTER TABLE proto_organization_membership RENAME TO organization_membership_candidate;

CREATE SEQUENCE organization_membership_candidate_sequence;

ALTER TABLE organization_membership_candidate ADD seq BIGINT(20) NOT NULL DEFAULT 0;

CREATE INDEX organization_membership_candidate_seq_index ON organization_membership_candidate(seq);

insert into evolutions (name, description) values('356.sql', 'add organization_membership_candidate_sequence');

# --- !Downs
