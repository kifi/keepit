# CURATOR

# --- !Ups

ALTER TABLE uri_recommendation
  DROP COLUMN vote;

ALTER TABLE uri_recommendation
  ADD COLUMN emailVote boolean DEFAULT NULL;

ALTER TABLE uri_recommendation
  ADD COLUMN vote boolean DEFAULT NULL;

ALTER TABLE uri_recommendation
  ADD COLUMN improvement text DEFAULT NULL;

insert into evolutions (name, description) values('234.sql', 'change vote to emailVote for userInteraction, add another vote for feedback, also add improvement column.');

# --- !Downs