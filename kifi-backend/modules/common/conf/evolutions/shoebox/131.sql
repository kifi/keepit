# SHOEBOX

# --- !Ups

alter TABLE electronic_mail
    add column extra_headers longtext NULL;

insert into evolutions (name, description) values('131.sql', 'adding extra headers column to electronic mail');

# --- !Downs
