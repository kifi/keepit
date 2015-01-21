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

alter table user_lda_stats
  add INDEX user_lda_stats_i_version_1st_2nd_3rd (version, first_topic, second_topic, third_topic);

INSERT INTO evolutions (name, description) VALUES('284.sql', 'add first 3 topics, major topic score to user_lda_stats table');

# --- !Downs
