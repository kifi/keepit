# SHOEBOX

# --- !Ups

ALTER TABLE email_address ADD COLUMN `hash` varchar(26) NOT NULL AFTER address;
ALTER TABLE email_address ADD UNIQUE INDEX email_address_u_hash (hash);
DROP INDEX email_address_i_address;

insert into evolutions (name, description) values('371.sql', 'add unique column email_address.hash');

# --- !Downs
