# SHOEBOX

# --- !Ups

ALTER TABLE library_invite
    CHANGE COLUMN passcode pass_phrase DEFAULT NULL;

ALTER TABLE library_membership
    ADD UNIQUE INDEX library_membership (library_id, user_id);

INSERT INTO evolutions (name, description) VALUES('250.sql', 'constraint on (library, user) & renames');

# --- !Downs
