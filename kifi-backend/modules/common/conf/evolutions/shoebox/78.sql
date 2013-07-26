# --- !Ups

CREATE TABLE search_friend (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    user_id bigint(20) NOT NULL,
    friend_id bigint(20) NOT NULL,

    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT search_friend_user_id FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT search_friend_friend_id FOREIGN KEY (friend_id) REFERENCES user(id),

    UNIQUE INDEX(user_id, friend_id)
);

insert into evolutions (name, description) values('78.sql', 'add search_friend table');

# --- !Downs
