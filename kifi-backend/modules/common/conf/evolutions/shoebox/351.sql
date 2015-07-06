# SHOEBOX

# --- !Ups

CREATE TABLE proto_organization_membership (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,
	proto_organization_id bigint(20) NOT NULL,
	user_id bigint(20) NOT NULL,

	PRIMARY KEY(id),
	CONSTRAINT `proto_organization_membership_f_organization` FOREIGN KEY (`organization_id`) REFERENCES organization(`id`),
	CONSTRAINT `proto_organization_membership_f_user` FOREIGN KEY (`user_id`) REFERENCES user(`id`),
	UNIQUE KEY `proto_organization_u_org_id_user_id` (`organization_id`, `user_id`)
);

insert into evolutions(name, description) values('351.sql', 'create proto_organization_membership');

# --- !Downs
