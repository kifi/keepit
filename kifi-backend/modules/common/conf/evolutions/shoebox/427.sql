# SHOEBOX

# --- !Ups

alter table keep_to_library add index keep_to_library_i_library_id_last_activity_at (library_id, last_activity_at);
alter table keep_to_library add index keep_to_library_i_organization_id_last_activity_at (organization_id, last_activity_at);

alter table keep_to_user add index keep_to_user_i_user_id_last_activity_at (user_id, last_activity_at);

insert into evolutions(name, description) values('427.sql', 'add indices for last_activity_at on keep_to_user and keep_to_library for feed sorting');

# --- !Downs
