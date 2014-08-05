# CURATOR
# --- !Ups

CREATE TABLE user_recommendation_generation_state (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    seq bigint(20) NOT NULL,
    user_id bigint(20) NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX user_recommendation_generation_state_u_user_id (user_id)
);



insert into evolutions (name, description) values('214.sql', 'adding user_recommendation_generation_state to curator');

# --- !Downs
