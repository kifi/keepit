# shoebox

# --- !ups

alter table library_alias modify column owner_id bigint(20) null;
alter table library_alias add column organization_id bigint(20) null after owner_id;


-- these constraints leverage mysql's property of not enforcing constraints involving null columns. h2 does...
--alter table library_alias
--	add constraint `library_alias_f_user` foreign key (`owner_id`) references user(`id`),
--	add constraint `library_alias_f_organization` foreign key (`organization_id`) references organization(`id`),
--  drop index library_alias_i_owner_id_slug,
--  add unique `library_alias_u_owner_id_slug` (`owner_id`,`slug`),
--  add unique `library_alias_u_organization_id_slug` (`organization_id`,`slug`);

insert into evolutions (name, description) values('347.sql', 'add organization_id, constraints to library_alias');

# --- !downs
