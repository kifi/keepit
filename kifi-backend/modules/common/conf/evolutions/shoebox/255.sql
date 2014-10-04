# SHOEBOX

# --- !Ups

ALTER TABLE library_membership
    ADD COLUMN last_email_sent datetime DEFAULT NULL;

insert into evolutions (name, description) values('255.sql', 'last_email_sent for library_membership');

# --- !Downs
