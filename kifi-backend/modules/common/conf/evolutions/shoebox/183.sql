# CORTEX

# --- !Ups

--- alter table uri_lda_topic
--- modify column sparse_feature TEXT DEFAULT NULL;

--- alter table uri_lda_topic
--- modify column feature blob DEFAULT NULL;

alter table uri_lda_topic
  alter column sparse_feature TEXT DEFAULT NULL;

alter table uri_lda_topic
  alter column feature blob DEFAULT NULL;

insert into evolutions (name, description) values('183.sql', 'make uri_lda_topic feature columns nullable');

# --- !Downs
