# CORTEX

# --- !Ups

alter table cortex_library_membership
    add index cortex_library_membership_i_membership_id (membership_id);

insert into evolutions (name, description) values('309.sql', 'add membership_id index to cortex_library_membership');

# --- !Downs
