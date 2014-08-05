# CURATOR

# --- !Ups

CREATE TABLE if not exists uri_recommendation (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,

    user_id bigint(20) NOT NULL,
    uri_id bigint(20) NOT NULL,
    master_score float(10) NOT NULL,
    all_scores text NOT NULL,
    seen boolean NOT NULL,
    clicked boolean NOT NULL,
    kept boolean NOT NULL,

    PRIMARY KEY (id),

    UNIQUE INDEX recommendation_u_uri_id_user_id (uri_id, user_id),
    INDEX recommendation_i_user_id_master_score (user_id, master_score)
);

insert into evolutions (name, description) values('213.sql', 'create uri recommendation table');

# --- !Downs
