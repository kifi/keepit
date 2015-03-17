# SHOEBOX

# --- !Ups

alter table bookmark
    add column source_attribution_id bigint(20) NULL;

insert into evolutions (name, description) values('304.sql', 'add column source_attribution_id to bookmark table');


# --- !Downs
