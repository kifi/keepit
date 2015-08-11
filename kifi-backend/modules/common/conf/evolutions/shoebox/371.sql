# SHOEBOX

# --- !Ups

alter table notification add COLUMN group_identifier VARCHAR(256) NOT NULL;

alter table notification add CONSTRAINT notification_group_identifier unique index(group_identifier);

insert into evolutions (name, description) values('371.sql', 'Add group_identifier to notification_item table');

# --- !Downs
