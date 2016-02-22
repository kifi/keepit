# SHOEBOX

# --- !Ups

create index ktl_i_added_at on keep_to_library(added_at);
create index ktu_i_added_at on keep_to_user(added_at);

create index bookmark_i_last_activity_at on bookmark(last_activity_at);
create index ktl_i_last_activity_at on keep_to_library(last_activity_at);
create index ktu_i_last_activity_at on keep_to_user(last_activity_at);

insert into evolutions (name, description) values('429.sql', 'add single-column last_activity_at and added_at indices on bookmark, ktl, and ktu');

# --- !Downs
