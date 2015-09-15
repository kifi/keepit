# SHOEBOX

# --- !Ups

ALTER TABLE account_event DROP COLUMN processing_stage;
ALTER TABLE account_event ADD COLUMN charge_id text DEFAULT NULL;

ALTER TABLE paid_account ADD COLUMN billing_cycle_start DATETIME;

insert into evolutions (name, description) values('394.sql', 'dropping proccessing_stage from account_event and adding charge_id, adding billing_cycle_start to paid_account');

# --- !Downs
