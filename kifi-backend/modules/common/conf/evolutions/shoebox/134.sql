# SHOEBOX

# --- !Ups

-- MySQL:
-- create table user_connection_sequence (id INT NOT NULL);
-- insert into user_connection_sequence values(0);
-- H2:
create sequence user_connection_sequence;
---

alter table user_connection add seq INT;
create index user_connection_seq_index on user_connection(seq);


-- MySQL:
-- create table search_friend_sequence (id INT NOT NULL);
-- insert into search_friend_sequence values(0);
-- H2:
create sequence search_friend_sequence;

alter table search_friend add seq INT;
create index search_friend_seq_index on search_friend(seq);

insert into evolutions (name, description) values ('134.sql', 'add seq number to user_connection and search_friend');
