# SHOEBOX

# --- !Ups

alter table export_request add column notify_email varchar(255);

insert into evolutions(name, description) values ('449.sql', 'add optional notify_email to export_request');

# --- !Downs

