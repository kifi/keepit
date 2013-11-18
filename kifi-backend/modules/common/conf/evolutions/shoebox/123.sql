# SHOEBOX

# --- !Ups

CREATE TABLE user_notify_preference (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    name varchar(64) NOT NULL,
    can_send boolean NOT NULL,
    state VARCHAR(20) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT user_notify_preference_f_user_id FOREIGN KEY (user_id) REFERENCES user(id)
);

insert into evolutions (name, description) values('123.sql', 'adding user_notify_preference table');

# --- !Downs
