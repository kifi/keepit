# --- !Ups

alter TABLE comment
    add column parent bigint(20);
    
insert into evolutions (name, description) values('12.sql', 'adding parent column to comment table');

# --- !Downs
