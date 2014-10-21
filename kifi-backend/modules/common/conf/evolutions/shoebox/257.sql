#Shoebox

# --- !Ups

ALTER TABLE library_invite ADD COLUMN inviter_id bigint(20) NOT NULL;
ALTER TABLE library_invite ADD CONSTRAINT library_invite_f_inviter FOREIGN KEY (inviter_id) REFERENCES user(id);
ALTER TABLE library_invite DROP CONSTRAINT IF EXISTS library_invite_f_owner;
ALTER TABLE library_invite DROP owner_id;

insert into evolutions (name, description) values('257.sql', 'drop library_invite.owner_id, add library_invite.inviter_id');

# --- !Downs
