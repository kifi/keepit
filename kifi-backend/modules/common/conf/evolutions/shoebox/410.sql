# SHOEBOX

# --- !Ups

ALTER TABLE email_address ADD domain varchar(256) NOT NULL DEFAULT '' AFTER address;
CREATE INDEX email_address_i_domain on email_address (domain);
insert into evolutions(name, description) values('410.sql', 'add email_address.domain, index on email_address.domain');

# --- !Downs
