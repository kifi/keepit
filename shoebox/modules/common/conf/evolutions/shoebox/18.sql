# --- !Ups

alter TABLE comment
    add column page_title varchar(1024) NOT NULL;

insert into evolutions (name, description) values('18.sql', 'adding page title to comment');

# --- !Downs
