#Shoebox

# --- !Ups
alter table invitation
  add column last_invited_at datetime,
	add column recipient_email_address varchar(512) ;

insert into evolutions (name, description) values('152.sql', 'add recipient_email_address, last_invited_at to invitation table');

# --- !Downs