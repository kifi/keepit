# ABOOK

# --- !Ups


CREATE TABLE organization_member_recommendation (
	id bigint(20) NOT NULL AUTO_INCREMENT UNIQUE,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	organization_id bigint(20) NOT NULL,
	member_id bigint(20) NOT NULL,
	recommended_user_id bigint(20),
	recommended_email_account_id bigint(20),
	irrelevant tinyint(1) NOT NULL,

	PRIMARY KEY (id),
	UNIQUE INDEX organization_member_recommendation_u_organization_id_member_id_recommended_user_id (organization_id, member_id, recommended_user_id),
	UNIQUE INDEX organization_member_recommendation_u_organization_id_member_id_recommended_email_account_id (organization_id, member_id, recommended_email_account_id)
);

insert into evolutions(name, description) values('352.sql', 'add organization_member_recommendation');

# --- !Downs
