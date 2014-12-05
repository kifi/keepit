# CURATOR

# --- !Ups

create index curator_library_membership_info_i_library_id on curator_library_membership_info (library_id);

insert into evolutions (name, description) values('269.sql', 'create index curator_library_membership_info_i_library_id');

# --- !Downs
