# CORTEX

# --- !Ups

alter table user_lda_interests add num_of_recent_evidence int(5) unsigned NOT NULL default 0;
alter table user_lda_interests add user_recent_topic_mean blob DEFAULT NULL;

insert into evolutions (name, description) values('203.sql', 'add recent profiles to user_lda_interests');

# --- !Downs
