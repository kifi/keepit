# Shoebox

# --- !Ups

CREATE TABLE membership_recommendation (
	id bigint(20) NOT NULL AUTO_INCREMENT UNIQUE,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	organization_id bigint(20) NOT NULL,
	member_id bigint(20) NOT NULL,
	irrelevant tinyint(1) NOT NULL,

	PRIMARY KEY (id),
	UNIQUE INDEX membership_recommendation_u_organization_id_member_id (organization_id, member_id)
);

CREATE TABLE organization_email_invite_recommendation (
    id bigint(20) NOT NULL AUTO_INCREMENT UNIQUE,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    organization_id bigint(20) NOT NULL,
    email_account_id bigint(20) NOT NULL,
    irrelevant tinyint(1) NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX organization_email_invite_recommendation_u_user_id_email_account_id (organization_id, email_account_id)
);

insert into evolutions(name, description) values('351.sql', 'add membership_recommendation, organization_email_invite_recommendation tables');

# --- !Downs
