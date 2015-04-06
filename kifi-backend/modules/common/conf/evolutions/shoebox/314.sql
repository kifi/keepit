# ROVER

# --- !Ups
alter table article_info
    add column domain varchar(512) NULL;

insert into evolutions (name, description) values('314.sql', 'add domain column to article_info');

# --- !Downs
