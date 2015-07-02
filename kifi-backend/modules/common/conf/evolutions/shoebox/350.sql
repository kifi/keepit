# SHOEBOX

# --- !Ups

CREATE TABLE proto_organization (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,
	name varchar(256) NOT NULL,
	description text DEFAULT NULL,
	owner_id bigint(20) NOT NULL,

	PRIMARY KEY(id),
	INDEX `proto_organization_i_seq` (`seq`),
	CONSTRAINT `proto_organization_f_user` FOREIGN KEY (`owner_id`) REFERENCES user(`id`)
);

CREATE TABLE proto_organization_membership (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,
	proto_organization_id bigint(20) NOT NULL,
	user_id bigint(20) DEFAULT NULL,
	email_address varchar(512) DEFAULT NULL,

	PRIMARY KEY(id),
	CONSTRAINT `proto_organization_membership_f_organization` FOREIGN KEY (`proto_organization_id`) REFERENCES proto_organization(`id`),
	CONSTRAINT `proto_organization_membership_f_user` FOREIGN KEY (`user_id`) REFERENCES user(`id`)
);

insert into evolutions(name, description) values('350.sql', 'create proto_organization, proto_organization_membership');

# --- !Downs
