# --- !Ups

CREATE TABLE follow (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    uri_id bigint(20) NOT NULL,
    state varchar(20) NOT NULL,
    
    PRIMARY KEY (id),

    UNIQUE INDEX follow_user_id_uri_id (user_id, uri_id),

    CONSTRAINT follow_user_id FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT follow_uri_id FOREIGN KEY (uri_id) REFERENCES normalized_uri(id)
);

insert into evolutions (name, description) values('14.sql', 'adding follow table');

# --- !Downs
