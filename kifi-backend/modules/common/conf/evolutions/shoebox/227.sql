# CORTEX

# --- !Ups

alter table uri_lda_topic
    add column first_topic_score FLOAT DEFAULT NULL;


insert into evolutions (name, description) values('227.sql', 'add first_topic_score to uri_lda_topic');

# --- !Downs
