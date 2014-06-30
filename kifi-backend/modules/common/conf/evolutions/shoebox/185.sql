# ELIZA

# --- !Ups
ALTER TABLE non_user_thread DROP COLUMN econtact_id
insert into evolutions (name, description) values('185.sql', 'drop econtact_id from non_user_thread');

# --- !Downs
