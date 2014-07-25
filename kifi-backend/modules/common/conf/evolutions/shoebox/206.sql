# SHOEBOX

# --- !Ups

-- MySQL:
-- CREATE TABLE library_sequence (id BIGINT(20) NOT NULL);
-- INSERT INTO library_sequence VALUES (0);
-- CREATE TABLE library_membership_sequence (id BIGINT(20) NOT NULL);
-- INSERT INTO library_membership_sequence VALUES (0);
-- H2:
CREATE SEQUENCE library_sequence;
CREATE SEQUENCE library_membership_sequence;

insert into evolutions (name, description) values('206.sql', 'adding new sequences for library and library_membership');


# --- !Downs
