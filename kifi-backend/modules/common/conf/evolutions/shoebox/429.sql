# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ADD COLUMN hseq BIGINT(20) DEFAULT NULL AFTER seq;

insert into evolutions (name, description) values('429.sql', 'track the human-initiated subsequence number (hseq)');

# --- !Downs
