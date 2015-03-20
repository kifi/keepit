# SHOEBOX

# --- !Ups

alter table raw_keep add column keep_tags text NULL;

insert into evolutions (name, description) values('306.sql', 'add column keep_tags to raw_keep table, depreciate tag_ids');

# --- !Downs
