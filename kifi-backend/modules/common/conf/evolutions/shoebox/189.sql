# ABOOK

# --- !Ups
ALTER TABLE econtact ADD COLUMN abook_id;
ALTER TABLE econtact DROP INDEX econtact_i_user_id_email ON econtact;
ALTER TABLE econtact ADD CONSTRAINT econtact_i_abook_id_email UNIQUE (abook_id, email)
insert into evolutions (name, description) values('189.sql', 'add abook_id to econtact');

# --- !Downs
