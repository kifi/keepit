# --- !Ups


CREATE TABLE deep_link (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    initiator_user_id  bigint(20),
    recipient_user_id bigint(20),
    uri_id bigint(20),
    deep_locator varchar(512) NOT NULL,
    token varchar(36) NOT NULL,
    state varchar(20) NOT NULL,
    
    PRIMARY KEY (id),

    UNIQUE INDEX deep_link_token (token),

    CONSTRAINT deep_link_initiator_user_id FOREIGN KEY (initiator_user_id) REFERENCES user(id),
    CONSTRAINT deep_link_recipient_user_id FOREIGN KEY (recipient_user_id) REFERENCES user(id),
    CONSTRAINT deep_link_uri_id FOREIGN KEY (uri_id) REFERENCES normalized_uri(id)
);

    
insert into evolutions (name, description) values('22.sql', 'added table deep_link');

# --- !Downs
