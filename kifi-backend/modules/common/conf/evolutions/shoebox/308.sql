# SHOEBOX

# --- !Ups

alter table twitter_sync_state
    add index twitter_sync_state_i_library (library_id);

insert into evolutions (name, description) values('305.sql', 'add library index to twitter_sync_table');

# --- !Downs
