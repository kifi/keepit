# SHOEBOX

# --- !Ups

ALTER TABLE library_invite
    DROP passcode;
ALTER TABLE library_invite
    ADD COLUMN pass_phrase varchar(30) NOT NULL;

ALTER TABLE library_membership
    ADD UNIQUE INDEX library_membership (library_id, user_id);

INSERT INTO evolutions (name, description) VALUES('250.sql', 'constraint on (library, user) & renames');

# --- !Downs
