# CORTEX

# --- !Ups

alter table user_lda_interests
    add column overall_snapshot_at datetime default null;

alter table user_lda_interests
    add column overall_snapshot blob default null;

alter table user_lda_interests
    add column recency_snapshot_at datetime default null;

alter table user_lda_interests
    add column recency_snapshot blob default null;

insert into evolutions (name, description) values('236.sql', 'add multiple columns related to shapshot to user_lda_interests');

# --- !Downs

