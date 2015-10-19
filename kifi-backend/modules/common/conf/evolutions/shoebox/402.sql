# SHOEBOX

# --- !Ups

ALTER TABLE paid_account ADD COLUMN payment_status VARCHAR(64) NOT NULL DEFAULT 'ok' AFTER credit;

insert into evolutions(name, description) values('402.sql', 'add column paid_account.payment_status');

# --- !Downs
