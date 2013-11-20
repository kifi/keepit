# SHOEBOX

# --- !Ups

ALTER TABLE invitation ADD COLUMN recipient_econtact_id bigint(20);
ALTER TABLE econtact   ADD COLUMN state varchar(20) DEFAULT 'active';

INSERT INTO evolutions (name, description) VALUES ('121.sql', 'add recipient_econtact_id to invitation and state to econtact');

# --- !Downs
