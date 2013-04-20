# --- !Ups

alter TABLE invitation
    alter column sender_user_id null;

insert into evolutions (name, description) values('56.sql', 'making sender user id in invitation nullable');

# --- !Downs
