# SHOEBOX

# --- !Ups
ALTER TABLE invitation DROP COLUMN recipient_econtact_id;
insert into evolutions (name, description) values('186.sql', 'drop recipient_econtact_id from invitation');

# --- !Downs
