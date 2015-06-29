# SHOEBOX

# --- !Ups

ALTER TABLE library_alias MODIFY COLUMN owner_id BIGINT(20) NULL;
ALTER TABLE library_alias ADD COLUMN organization_id BIGINT(20) NULL AFTER owner_id;


-- These constraints leverage MySQL's property of not enforcing constraints involving null columns. H2 does...
--ALTER TABLE library_alias
--	ADD CONSTRAINT `library_alias_f_user` FOREIGN KEY (`owner_id`) REFERENCES user(`id`),
--	ADD CONSTRAINT `library_alias_f_organization` FOREIGN KEY (`organization_id`) REFERENCES organization(`id`),
--  DROP INDEX library_alias_i_owner_id_slug,
--  ADD UNIQUE `library_alias_u_owner_id_slug` (`owner_id`,`slug`),
--  ADD UNIQUE `library_alias_u_organization_id_slug` (`organization_id`,`slug`);

insert into evolutions (name, description) values('347.sql', 'add organization_id, constraints to library_alias');

# --- !Downs
