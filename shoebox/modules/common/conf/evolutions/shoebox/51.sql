# --- !Ups

ALTER TABLE electronic_mail ADD in_reply_to varchar(128);

insert into evolutions (name, description) values('51.sql', 'add in_reply_to to electronic_mail');

# --- !Downs
