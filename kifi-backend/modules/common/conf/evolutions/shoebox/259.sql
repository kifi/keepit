#Shoebox

# --- !Ups

ALTER TABLE library_membership ADD CONSTRAINT library_membership_u_library_user UNIQUE (library_id, user_id);

insert into evolutions (name, description) values('259.sql', 'add unique constraint on (libraryId, userId) for library_membership');

# --- !Downs
