# ABOOK

# --- !Ups

CREATE TABLE organization_recommendation_for_user (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    organization_id bigint(20) NOT NULL,
    irrelevant tinyint(1) NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX organization_recommendation_for_user_u_user_id_organization_id (user_id, organization_id)
);

insert into evolutions (name, description) values('357.sql', 'adding organization_recommendation_for_user');

# --- !Downs
