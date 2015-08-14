# SHOEBOX

# --- !Ups


alter table account_event
    add UNIQUE INDEX account_event_u_event_group (event_group);

alter table account_event
    add INDEX account_event_i_account_id_event_time (account_id, event_time);


insert into evolutions (name, description) values('376.sql', 'better indexing for account_event');

# --- !Downs
