# ABOOK

# --- !Ups
ALTER TABLE econtact ADD COLUMN abook_id;
insert into evolutions (name, description) values('189.sql', 'add abook_id to econtact');

# --- !Downs
