# SHOEBOX

# --- !Ups

alter table notification_item add COLUMN group_identifier VARCHAR(256) NOT NULL;

insert into evolutions (name, description) values('371.sql', 'Add group_identifier to notification_item table');

# --- !Downs
