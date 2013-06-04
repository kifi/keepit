# --- !Ups

CREATE TABLE user (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    state varchar(20) NOT NULL,

    first_name varchar(256) NOT NULL,
    last_name varchar(256) NOT NULL,
    facebook_id varchar(16) NOT NULL,
    facebook_access_token varchar(512),
    primary_email_id bigint(20),
    
    PRIMARY KEY (id),
    
    CONSTRAINT user_u_external_id UNIQUE (external_id) 
);

CREATE TABLE normalized_uri (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    state varchar(20) NOT NULL,

    title varchar(2048) NOT NULL,
    url varchar(2048) NOT NULL,
    url_hash varchar(26) NOT NULL,
    is_private boolean,
    
    KEY (url_hash),
    PRIMARY KEY (id),
    
    CONSTRAINT normalized_uri_u_external_id UNIQUE (external_id), 
    CONSTRAINT normalized_uri_u_url_hash UNIQUE (url_hash) 
);

CREATE TABLE bookmark (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    state varchar(20) NOT NULL,

    user_id bigint(20) NOT NULL,
    title varchar(2048) NOT NULL,
    url varchar(2048) NOT NULL,
    uri_id bigint(20) NOT NULL,
    bookmark_path varchar(256),
    is_private boolean,
    
    KEY (user_id, uri_id),
    PRIMARY KEY (id),
    
    CONSTRAINT bookmark_u_external_id UNIQUE (external_id),
    CONSTRAINT bookmark_f_user FOREIGN KEY (user_id) REFERENCES user(id), 
    CONSTRAINT bookmark_f_uri FOREIGN KEY (uri_id) REFERENCES normalized_uri(id) 
);

CREATE TABLE email_address (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    address varchar(512) NOT NULL,
    user_id bigint(20),
    state varchar(128) NOT NULL,
    verified_at datetime NULL,
    last_verification_sent datetime NULL,
    
    PRIMARY KEY (id),
    
    CONSTRAINT web_session_f_email FOREIGN KEY (user_id) REFERENCES user(id)    
);

ALTER TABLE user ADD CONSTRAINT user_f_email FOREIGN KEY (primary_email_id) REFERENCES email_address(id);

CREATE TABLE electronic_mail (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,

    user_id bigint(20), 
    from_addr varchar(256) NOT NULL,
    to_addr varchar(256) NOT NULL,
    subject varchar(1024) NOT NULL, 
    state varchar(20) NOT NULL,
    html_body MEDIUMTEXT NOT NULL, 
    text_body MEDIUMTEXT,
    response_message varchar(1024),
    time_submitted datetime,
    
    PRIMARY KEY (id),
    key(state),
    
    CONSTRAINT electronic_mail_f_user FOREIGN KEY (user_id) REFERENCES user(id)    
);

CREATE TABLE evolutions (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at timestamp NOT NULL,
    name varchar(64) NOT NULL,
    description varchar(512) DEFAULT '',

    PRIMARY KEY (id),
    CONSTRAINT evolutions_u_name UNIQUE (name)
);

insert into evolutions (name, description) values('1.sql', 'adding user and bookmark');

# --- !Downs
