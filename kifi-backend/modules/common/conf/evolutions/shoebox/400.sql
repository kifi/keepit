# SHOEBOX

# --- !Ups

ALTER TABLE account_event DROP COLUMN event_group;
ALTER TABLE paid_account DROP COLUMN modified_since_last_integrity_check;

insert into evolutions(name, description) values('400.sql', 'removing deprecated columns');

# --- !Downs
