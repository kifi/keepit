# SHOEBOX

# --- !Ups

CREATE TABLE user_picture (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    user_id bigint(20) NOT NULL,
    name varchar(16) NOT NULL,
    origin varchar(64) NOT NULL,
    state varchar(20) NOT NULL,

    KEY (user_id, name),
    PRIMARY KEY (id),
    CONSTRAINT user_picture_f_user_id FOREIGN KEY (user_id) REFERENCES user(id)
);

alter TABLE user
    add column picture_name varchar(16) NULL;

alter TABLE user
    add column user_picture_id bigint(20) NULL;

alter TABLE user
    add CONSTRAINT user_f_user_picture FOREIGN KEY (user_picture_id) REFERENCES user_picture(id);


insert into evolutions (name, description) values('112.sql', 'create user_picture table, add picture_name and user_picture_id to user table');

# --- !Downs
