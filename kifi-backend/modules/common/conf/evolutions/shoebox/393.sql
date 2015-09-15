# SHOEBOX

# --- !Ups

ALTER TABLE paid_account ADD COLUMN modified_since_last_integrity_check boolean DEFAULT false;
ALTER TABLE paid_account ADD COLUMN active_users int(11) DEFAULT 0;

insert into evolutions (name, description) values('393.sql', 'adding modified_since_last_integrity_check and active_users columns to paid_account');

# --- !Downs
