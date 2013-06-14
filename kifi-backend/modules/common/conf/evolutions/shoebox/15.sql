# --- !Ups

alter table electronic_mail
  alter column message_id varchar(128);

insert into evolutions (name, description) values('15.sql', 'enlarging the message_id in email table');

# --- !Downs
