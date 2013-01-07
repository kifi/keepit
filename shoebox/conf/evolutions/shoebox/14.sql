# --- !Ups

CREATE TABLE "FOLLOW" (
    "ID" bigint(20) NOT NULL AUTO_INCREMENT,
    "CREATED_AT" datetime NOT NULL,
    "UPDATED_AT" datetime NOT NULL,
    "USER_ID" bigint(20) NOT NULL,
    "URI_ID" bigint(20) NOT NULL,
    "STATE" varchar(20) NOT NULL,
    
    PRIMARY KEY (id),

    UNIQUE INDEX follow_user_id_uri_id (user_id, uri_id),

    CONSTRAINT follow_user_id FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT follow_uri_id FOREIGN KEY (uri_id) REFERENCES normalized_uri(id)
);

insert into evolutions (name, description) values('14.sql', 'adding follow table');

# --- !Downs
