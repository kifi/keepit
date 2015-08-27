# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ADD INDEX bookmark_i_library_id_kept_at (library_id, kept_at);

ALTER TABLE keep_to_library ADD INDEX keep_to_library_i_library_id_added_at (library_id, added_at);
ALTER TABLE keep_to_library ADD INDEX keep_to_library_i_organization_id_added_at (organization_id, added_at);

ALTER TABLE keep_to_user ADD INDEX keep_to_user_i_user_id_added_at (user_id, added_at);

insert into evolutions (name, description) values('384.sql', 'add indices for chrono feed on bookmark, keep_to_library, and keep_to_user');

# --- !Downs
