# CORTEX

# --- !Ups

alter table user_lda_stats
    add column first_topic smallint unsigned default NULL;

alter table user_lda_stats
  add column second_topic smallint unsigned default NULL;

alter table user_lda_stats
  add column third_topic smallint unsigned default NULL;

alter table user_lda_stats
  add column first_topic_score real default NULL;

INSERT INTO evolutions (name, description) VALUES('283.sql', 'add first 3 topics, major topic score to user_lda_stats table');

# --- !Downs
