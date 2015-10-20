# SHOEBOX

# --- !Ups

ALTER TABLE account_event DROP COLUMN billing_related;

insert into evolutions(name, description) values('403.sql', 'drop account_event.billing_related');

# --- !Downs
