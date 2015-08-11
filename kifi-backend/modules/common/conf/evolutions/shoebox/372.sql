# ABOOK

# --- !Ups

ALTER TABLE email_account ADD COLUMN `hash` varchar(26) NOT NULL AFTER address;
ALTER TABLE email_account ADD UNIQUE INDEX email_account_u_hash (hash);
DROP INDEX email_account_i_address on email_account;

insert into evolutions (name, description) values('372.sql', 'add unique column email_account.hash');

# --- !Downs
