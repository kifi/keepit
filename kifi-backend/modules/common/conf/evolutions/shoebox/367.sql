# SHOEBOX

# --- !Ups

ALTER TABLE organization_avatar DROP CONSTRAINT organization_logo_u_source_file_hash_size_organization_id;

insert into evolutions (name, description) values('367.sql', 'Drop the unique constraint on source_file_hash for org avatars');

# --- !Downs
