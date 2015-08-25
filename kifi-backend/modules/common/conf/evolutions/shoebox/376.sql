# SHOEBOX

# --- !Ups


alter table account_event
    alter column event_group varchar(36);

alter table account_event
    add INDEX account_event_i_account_id_event_time (account_id, event_time);

alter table account_event
    add column processing_stage VARCHAR(20) NOT NULL;

alter table paid_account
    add column kind VARCHAR(20) NOT NULL;


insert into evolutions (name, description) values('376.sql', 'better indexing for account_event');

# --- !Downs
