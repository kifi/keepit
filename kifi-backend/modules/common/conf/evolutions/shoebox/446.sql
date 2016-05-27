# SHOEBOX

# --- !Ups

alter table twitter_waitlist modify column twitter_handle varchar(16);

insert into evolutions(name, description) values ('446.sql', 'make twitter handle nullable in twitter_waitlist');

# --- !Downs
