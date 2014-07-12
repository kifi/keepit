# ABOOK

# --- !Ups

ALTER TABLE econtact MODIFY abook_id bigint(20) NOT NULL;
ALTER TABLE econtact MODIFY email_account_id bigint(20) NOT NULL;

ALTER TABLE econtact ADD CONSTRAINT econtact_f_email_account FOREIGN KEY (email_account_id) REFERENCES email_account(id);
ALTER TABLE econtact ADD CONSTRAINT econtact_f_abook_info FOREIGN KEY (abook_id) REFERENCES abook_info(id);

insert into evolutions (name, description) values('193.sql', 'disallow null for abook_id, email_account_id and add foreign key constraints to econtact');

# --- !Downs
