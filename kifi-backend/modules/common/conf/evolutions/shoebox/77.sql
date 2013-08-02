# --- !Ups

CREATE TABLE friend_request (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    sender_id bigint(20) NOT NULL,
    recipient_id bigint(20) NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT friend_request_f_sender_id FOREIGN KEY (sender_id) REFERENCES user(id),
    CONSTRAINT friend_request_f_recipient_id FOREIGN KEY (recipient_id) REFERENCES user(id),
    INDEX friend_request_i_sender_id(sender_id),
    INDEX friend_request_i_recipient_id(recipient_id),
    INDEX friend_request_i_sender_recipient(sender_id, recipient_id)
);

insert into evolutions (name, description) values('77.sql', 'adding friend_request table');

# --- !Downs
