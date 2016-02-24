# SHOEBOX

# --- !Ups

alter table bookmark modify last_activity_at datetime not null;
alter table keep_to_library modify last_activity_at datetime not null;
alter table keep_to_user modify last_activity_at datetime not null;

insert into evolutions(name, description) values('429.sql', 'make last_activity not null on bookmark, keep_to_user, keep_to_library');

# --- !Downs
