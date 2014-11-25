# CORTEX

# --- !Ups

alter table library_lda_topic
    add column first_topic smallint unsigned default NULL;

alter table library_lda_topic
  add column second_topic smallint unsigned default NULL;

alter table library_lda_topic
  add column third_topic smallint unsigned default NULL;

alter table library_lda_topic
  add column first_topic_score real default NULL;

alter table library_lda_topic
  add column entropy real default NULL;

INSERT INTO evolutions (name, description) VALUES('264.sql', 'add first 3 topics, major topic score, entropy to library_lda_topic table');

# --- !Downs
