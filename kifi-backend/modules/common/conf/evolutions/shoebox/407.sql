# SHOEBOX

# --- !Ups

ALTER TABLE paid_account DROP COLUMN billing_cycle_start;
insert into evolutions(name, description) values('407.sql', 'drop column paid_account.billing_cycle_start');

# --- !Downs
