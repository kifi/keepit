# --- !Ups

CREATE TABLE user_notification (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    user_id bigint(20) NOT NULL,
    external_id varchar(36) NOT NULL,
    category varchar(36) NOT NULL,
    details TEXT NOT NULL,
    state VARCHAR(20) NOT NULL,
    comment_id bigint(20),
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT user_notification_f_user FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT user_notification_f_comment_id FOREIGN KEY (comment_id) REFERENCES comment(id)
);

insert into evolutions (name, description) values('47.sql', 'adding user_notification table');

# --- !Downs
