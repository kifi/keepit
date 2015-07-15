# SHOEBOX

# --- !Ups

CREATE TABLE organization_membership_poke (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,
	organization_id bigint(20) NOT NULL,
	user_id bigint(20) NOT NULL,

	PRIMARY KEY(id),
	CONSTRAINT `organization_membership_f_organization` FOREIGN KEY (`organization_id`) REFERENCES organization(`id`),
	CONSTRAINT `organization_membership_f_user` FOREIGN KEY (`user_id`) REFERENCES user(`id`)
);

insert into evolutions(name, description) values('357.sql', 'added organization_membership_poke');

# --- !Downs
