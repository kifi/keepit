# CuRATOR

# --- !Ups

CREATE TABLE if not exists uri_recommendation (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,

    user_id bigint(20) NOT NULL,
    uri_id bigint(20) NOT NULL,
    master_score(10) float NOT NULL,
    all_score varchar(256) NOT NULL,
    seen boolean NOT NULL,
    clicked boolean NOT NULL,
    kept boolean NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT user_u_external_id UNIQUE (external_id),

    UNIQUE INDEX recommendation_u_user_id_uri_id (user_id, uri_id)
);

insert into evolutions (name, description) values('213.sql', 'create uri recommendation table');

# --- !Downs
