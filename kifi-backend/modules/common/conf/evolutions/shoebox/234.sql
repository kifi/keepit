# CURATOR

# --- !Ups

ALTER TABLE uri_recommendation
  DROP COLUMN delivered;

ALTER TABLE uri_recommendation
  ADD COLUMN email_vote boolean DEFAULT NULL;

ALTER TABLE uri_recommendation
  ADD COLUMN improvement text DEFAULT NULL;

ALTER TABLE uri_recommendation
    ADD COLUMN from_client varchar(256) DEFAULT NULL;

insert into evolutions (name, description) values('234.sql', 'change vote to emailVote for userInteraction, add another vote for feedback, also add improvement and from_client column.');

# --- !Downs
