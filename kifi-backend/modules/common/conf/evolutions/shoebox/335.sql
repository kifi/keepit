# Shoebox

# --- !Ups
CREATE TABLE organization (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,
	seq bigint(20) NOT NULL,
	name varchar(256) NOT NULL,
	description text DEFAULT '',
	owner_id bigint(20) NOT NULL,
	organization_handle varchar(64) DEFAULT NULL,
	normalized_organization_handle varchar(64) DEFAULT NULL,

	PRIMARY KEY(id),
	UNIQUE KEY `organization_u_organization_handle` (`organization_handle`),
	UNIQUE KEY `organization_u_normalized_organization_handle` (`normalized_organization_handle`),
	INDEX KEY `organization_i_seq` (`seq`),
	CONSTRAINT `organization_f_user` FOREIGN KEY (`owner_id`) REFERENCES user(`id`)
);

CREATE TABLE organization_membership (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,
	seq bigint(20) NOT NULL,
	organization_id bigint(20) NOT NULL,
	user_id bigint(20) NOT NULL,
	access varchar(20) NOT NULL,

	PRIMARY KEY(id),
	CONSTRAINT `organization_membership_f_organization` FOREIGN KEY (`organization_id`) REFERENCES organization(`id`),
	CONSTRAINT `organization_membership_f_user` FOREIGN KEY (`user_id`) REFERENCES user(`id`)
);

CREATE TABLE organization_invite (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,
	organization_id bigint(20) NOT NULL,
	inviter_id bigint(20) NOT NULL,
	user_id bigint(20) DEFAULT NULL,
	email_address varchar(512) DEFAULT NULL,
	access varchar(20) NOT NULL,
	message text DEFAULT NULL,

	PRIMARY KEY(id),
	CONSTRAINT `organization_invite_f_organization` FOREIGN KEY (`organization_id`) REFERENCES organization(`id`),
	CONSTRAINT `organization_invite_f_inviter_user` FOREIGN KEY (`inviter_id`) REFERENCES user(`id`),
	CONSTRAINT `organization_invite_f_invited_user` FOREIGN KEY (`user_id`) REFERENCES user(`id`)
);

CREATE TABLE organization_logo (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,
	organization_id bigint(20) NOT NULL,
	x_position smallint unsigned,
	y_position smallint unsigned,
	width smallint(5) unsigned NOT NULL,
	height smallint(5) unsigned NOT NULL,
	format varchar(16) NOT NULL,
	kind varchar(32) NOT NULL,
	path varchar(64) NOT NULL,
	source varchar(32) NOT NULL,
	source_file_hash varchar(32) DEFAULT NULL,
	source_image_url varchar(3072) DEFAULT NULL,

	PRIMARY KEY(id),
	CONSTRAINT `organization_logo_f_organization` FOREIGN KEY (`organization_id`) REFERENCES organization(`id`),
	UNIQUE KEY `organization_logo_u_source_file_hash_size_organization_id` (`source_file_hash`, `width`, `height`, `organization_id`)
);

ALTER TABLE `library`
	ADD COLUMN `organization_id` bigint(20) DEFAULT NULL;
ALTER TABLE `library`
	ADD CONSTRAINT `library_f_organization` FOREIGN KEY (`organization_id`) REFERENCES organization(`id`);

insert into evolutions(name, description) values('336.sql', 'create organization, organization membership, organization_invite, and organization_logo tables, adds org_id added to library');

# --- !Downs
