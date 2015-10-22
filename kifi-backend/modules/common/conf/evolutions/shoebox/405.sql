# SHOEBOX

# --- !Ups

ALTER TABLE paid_account ADD COLUMN payment_due_at datetime DEFAULT NULL AFTER credit;

insert into evolutions(name, description) values('405.sql', 'add column paid_account.payment_due_at');

# --- !Downs
