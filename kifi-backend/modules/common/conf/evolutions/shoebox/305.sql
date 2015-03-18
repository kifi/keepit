# ELIZA

# --- !Ups

alter table device add column signature varchar(40) NULL;

create unique index device_u_user_id_device_type_signature on device (user_id, device_type, signature);

insert into evolutions (name, description) values('305.sql', 'add column signature to device table');

# --- !Downs
