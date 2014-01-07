# SHOEBOX

# --- !Ups

DROP TABLE comment_recipient;
DROP TABLE user_notification;
DROP TABLE comment_read;
DROP TABLE comment;

insert into evolutions (name, description) values('129.sql', 'dropping the comment, comment_recipient, user_notification and comment_read tables');

# --- !Downs
