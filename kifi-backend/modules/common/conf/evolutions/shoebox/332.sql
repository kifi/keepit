# SHOEBOX

# --- !Ups
ALTER TABLE library_invite MODIFY pass_phrase varchar(26) null;

insert into evolutions (name, description) values('332.sql', 'make pass_phrase nullable in library_invite');

# --- !Downs
