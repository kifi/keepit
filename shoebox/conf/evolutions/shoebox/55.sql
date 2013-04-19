# --- !Ups

CREATE TABLE user_session (
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    external_id VARCHAR(36) NOT NULL,
    user_id BIGINT(20),
    social_id VARCHAR(36) NOT NULL,
    provider VARCHAR(36) NOT NULL,
    expires DATETIME NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    INDEX user_session_external_id (external_id)
);

insert into evolutions (name, description) values('55.sql', 'adding user session table');

# --- !Downs
