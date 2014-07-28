# ABOOK

# --- !Ups

CREATE INDEX econtact_i_email_state ON econtact (email, state);

insert into evolutions (name, description) values('208.sql', 'add index to econtact (email,state)');

# --- !Downs
