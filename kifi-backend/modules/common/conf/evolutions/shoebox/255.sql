# SHOEBOX

# --- !Ups

ALTER TABLE library_membership
    ADD COLUMN last_email_sent datetime DEFAULT NULL;

ALTER TABLE library_membership ADD INDEX library_membership_i_last_email_sent (last_email_sent);
ALTER TABLE library_membership ADD INDEX library_membership_i_last_viewed (last_viewed);

insert into evolutions (name, description) values('255.sql', 'last_email_sent for library_membership');

# --- !Downs
