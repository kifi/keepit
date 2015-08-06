# SHOEBOX

# --- !Ups

ALTER TABLE email_address ADD COLUMN `primary` boolean NULL AFTER state;
ALTER TABLE email_address ADD UNIQUE INDEX email_address_u_user_id_primary (user_id, `primary`);

insert into evolutions (name, description) values('369.sql', 'add column email_address.primary');

# --- !Downs
