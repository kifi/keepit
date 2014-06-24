# SHOEBOX

# --- !Ups

create unique index electronic_mail_external_id_i on electronic_mail(external_id);

insert into evolutions (name, description) values ('180.sql', 'add unique index to external_id in electronic_mail table');

# --- !Downs
