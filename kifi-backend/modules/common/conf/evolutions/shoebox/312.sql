# SHOEBOX

# --- !Ups

alter table activity_push_task
    add column next_push datetime NULL;

alter table activity_push_task
    add column backoff bigint(20) NULL;

insert into evolutions (name, description) values('312.sql', 'add next_push and backoff to activity_push_task table');

# --- !Downs
