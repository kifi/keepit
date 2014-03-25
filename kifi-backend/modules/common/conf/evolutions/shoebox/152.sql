#Shoebox

# --- !Ups
alter table invitation add column last_sent_at datetime;
alter table invitation add column recipient_email_address varchar(512);

insert into evolutions (name, description) values('152.sql', 'add recipient_email_address, last_sent_at to invitation table');

# --- !Downs