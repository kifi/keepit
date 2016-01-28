# SHOEBOX

# --- !Ups

alter table keep_to_library add column last_activity_at datetime not null;
alter table keep_to_user add column last_activity_at datetime not null;

insert into evolutions(name, description) values('426.sql', 'add last_activity_at to keep_to_library, keep_to_user');

# --- !Downs
