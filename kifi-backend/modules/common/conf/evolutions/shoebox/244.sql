# SHOEBOX

# --- !Ups

alter table raw_keep
    add column library_id bigint(20) default null;
alter table raw_keep
    add constraint `raw_keep_f_library` foreign key (`library_id`) references `library` (`id`);

insert into evolutions (name, description) values('243.sql', 'add library id to raw keep table');

# --- !Downs