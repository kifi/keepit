# ELIZA

# --- !Ups

CREATE TABLE non_user_thread (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    kind varchar(32) NOT NULL,
    email_address varchar(512) NULL,
    econtact_id bigint(20) NULL,
    thread_id bigint(20) NOT NULL,
    uri_id bigint(20) NOT NULL,
    notified_count int NOT NULL,
    last_notified_at datetime NULL,
    thread_updated_at datetime NULL,
    muted boolean,

    state varchar(20) NOT NULL,

    KEY (email_address),
    KEY (econtact_id),
    KEY (last_notified_at),
    PRIMARY KEY (id),
    CONSTRAINT non_user_thread_f_thread_id FOREIGN KEY (thread_id) REFERENCES message_thread(id)
);

insert into evolutions (name, description) values('127.sql', 'create non_user_thread table');

# --- !Downs

DROP TABLE non_user_thread;
