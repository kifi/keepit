# --- !Ups

CREATE TABLE user_connection (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    user_1 bigint(20) NOT NULL,
    user_2 bigint(20) NOT NULL,

    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT user_connection_user_1 FOREIGN KEY (user_1) REFERENCES user(id),
    CONSTRAINT user_connection_user_2 FOREIGN KEY (user_2) REFERENCES user(id),
    UNIQUE INDEX(user_1, user_2)
);

insert into evolutions (name, description) values('57.sql', 'add user_connection table');

# --- !Downs
