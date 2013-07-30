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
    replyable bool(1) NOT NULL,

    PRIMARY KEY (id),
    KEY ext_id (external_id),
    KEY participants_uri (uri_id, participants_hash)
);

CREATE TABLE message (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,

    sender bigint(20) NULL,
    thread bigint(20) NOT NULL,
    message_text longtext NOT NULL,
    sent_on_url text NULL,
    sent_on_uri_id bigint(20) NULL,

    PRIMARY KEY (id),
    KEY my_thread (thread)  
);

CREATE TABLE user_thread (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    user bigint(20) NOT NULL,
    thread bigint(20) NOT NULL,
    uri_id bigint(20) DEFAULT NULL,
    last_seen datetime DEFAULT NULL,
    notification_pending bool(1) NOT NULL,
    muted bool(1) NOT NULL,
    last_msg_from_other bigint(20) DEFAULT NULL,
    lastNotification longtext DEFAULT NULL,

    PRIMARY KEY (id),
    KEY user_page (user, uri_id),
    UNIQUE KEY user_thread (user, thread)
);


insert into evolutions (name, description) values('1.sql', 'adding new tables for chat');



# --- !Downs
