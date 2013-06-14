# --- !Ups

alter TABLE electronic_mail
    add column cc_addr varchar(512);


insert into evolutions (name, description) values('58.sql', 'add cc to the electronic mail');

# --- !Downs
