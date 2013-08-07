# --- !Ups

CREATE TABLE message_thread (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL, 

    uri_id bigint(20) NULL,
    url text NULL,
    participants mediumtext NULL,
    participants_hash int(11) NULL,
    replyable bool NOT NULL,

    PRIMARY KEY (id),
    KEY message_thread_i_ext_id (external_id),
    KEY message_thread_i_participants_uri (uri_id, participants_hash)
);

CREATE TABLE message (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,

    sender_id bigint(20) NULL,
    thread_id bigint(20) NOT NULL,
    thread_ext_id varchar(36) NOT NULL,
    message_text longtext NOT NULL,
    sent_on_url text NULL,
    sent_on_uri_id bigint(20) NULL,

    PRIMARY KEY (id),
    KEY message_i_my_thread (thread_id),  

    FOREIGN KEY (thread_id)
      REFERENCES message_thread(id)
      ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE TABLE user_thread (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    user_id bigint(20) NOT NULL,
    thread_id bigint(20) NOT NULL,
    uri_id bigint(20) DEFAULT NULL,
    last_seen datetime DEFAULT NULL,
    notification_pending bool NOT NULL,
    muted bool NOT NULL,
    last_msg_from_other bigint(20) DEFAULT NULL,
    last_notification longtext DEFAULT NULL,
    notification_updated_at datetime NOT NULL,
    notification_last_seen datetime NOT NULL,

    PRIMARY KEY (id),
    KEY user_thread_i_user_page (user_id, uri_id),
    UNIQUE KEY user_thread_i_user_thread (user_id, thread_id),

    FOREIGN KEY (thread_id)
      REFERENCES message_thread(id)
      ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE TABLE evolutions (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at timestamp NOT NULL,
    name varchar(64) NOT NULL,
    description varchar(512) DEFAULT '',

    PRIMARY KEY (id),
    CONSTRAINT evolutions_u_name UNIQUE (name)
);


insert into evolutions (name, description) values('1.sql', 'adding new tables for chat');



# --- !Downs
