# --- !Ups

alter TABLE electronic_mail
    add column message_id varchar(64);
    
alter TABLE electronic_mail
    add column category varchar(64);
    
insert into evolutions (name, description) values('13.sql', 'adding message id and category to electronic mail');

# --- !Downs
