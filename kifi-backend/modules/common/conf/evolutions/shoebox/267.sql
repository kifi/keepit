# CURATOR

# --- !Ups

CREATE TABLE library_recommendation (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,

    user_id bigint(20) NOT NULL,
    library_id bigint(20) NOT NULL,
    master_score float(10) NOT NULL,
    all_scores text NOT NULL,
    followed boolean NOT NULL,

    PRIMARY KEY (id),

    UNIQUE INDEX library_recommendation_u_library_id_user_id (library_id, user_id),
    INDEX library_recommendation_i_user_id_master_score (user_id, master_score)
);

insert into evolutions (name, description) values('267.sql', 'create library_recommendation table');

# --- !Downs
