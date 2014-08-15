# CORTEX

# --- !Ups

alter table uri_lda_topic
    add column first_topic_score real DEFAULT NULL;

alter table uri_lda_topic
    add column times_first_topic_changed smallint unsigned DEFAULT 0;

insert into evolutions (name, description) values('227.sql', 'add first_topic_score to uri_lda_topic');

# --- !Downs
