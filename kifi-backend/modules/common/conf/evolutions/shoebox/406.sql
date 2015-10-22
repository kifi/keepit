# SHOEBOX

# --- !Ups

ALTER TABLE paid_account ADD COLUMN plan_renewal datetime NOT NULL AFTER credit;
CREATE INDEX paid_account_i_plan_renewal on paid_account(plan_renewal);
insert into evolutions(name, description) values('406.sql', 'add column paid_account.plan_renewal');

# --- !Downs
