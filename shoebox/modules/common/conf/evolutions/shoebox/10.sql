# --- !Ups

CREATE TABLE comment (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    normalized_uri_id bigint(20) NOT NULL,
    user_id bigint(20) NOT NULL,
    text text NOT NULL,
    permissions varchar(20) NOT NULL,
    state varchar(20) NOT NULL,
    
    PRIMARY KEY (id),
    
    UNIQUE INDEX(external_id),
    
    CONSTRAINT comment_user_id FOREIGN KEY (user_id) REFERENCES user(id)
);

CREATE TABLE comment_recipient (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    comment_id bigint(20),
    user_id bigint(20),
    social_user_id bigint(20),
    email varchar(512),
    state varchar(20) NOT NULL,
    
    PRIMARY KEY (id),
    
    CONSTRAINT comment_recipient_comment_id FOREIGN KEY (comment_id) REFERENCES comment(id),
    CONSTRAINT comment_recipient_user_id FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT comment_recipient_social_user_id FOREIGN KEY (social_user_id) REFERENCES social_user_info(id)
);

insert into evolutions (name, description) values('10.sql', 'adding comment and comment_recipient tables');

# --- !Downs
