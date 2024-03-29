# ABOOK

# --- !Ups

-- ALTER TABLE email_account_sequence MODIFY id bigint(20);

ALTER TABLE econtact ADD COLUMN abook_id bigint(20) NULL;
ALTER TABLE econtact ADD COLUMN email_account_id bigint(20) NULL;

ALTER TABLE econtact DROP CONSTRAINT econtact_i_user_id_email;
-- DROP INDEX econtact_i_user_id_email ON econtact;

CREATE UNIQUE INDEX econtact_i_abook_id_email_account_id ON econtact(abook_id, email_account_id);
CREATE INDEX econtact_i_user_id ON econtact(user_id);

insert into evolutions (name, description) values('191.sql', 'add abook_id, email_account_id to econtact');

# --- !Downs
