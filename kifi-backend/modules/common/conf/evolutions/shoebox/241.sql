# CORTEX

# --- !Ups

alter table cortex_keep add column library_id bigint(20) null;

alter table cortex_keep add column visibility varchar(32) default null;

insert into evolutions (name, description) values('241.sql', 'add library_id, visibility to keep_cortex');

# --- !Downs
