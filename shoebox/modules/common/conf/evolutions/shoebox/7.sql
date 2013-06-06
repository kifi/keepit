# --- !Ups

CREATE TABLE social_connection (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    social_user_1 bigint(20) NOT NULL,
    social_user_2 bigint(20) NOT NULL,

    state varchar(20) NOT NULL,
    
    PRIMARY KEY (id),
    
    CONSTRAINT social_connection_social_user_1 FOREIGN KEY (social_user_1) REFERENCES social_user_info(id),
    CONSTRAINT social_connection_social_user_2 FOREIGN KEY (social_user_2) REFERENCES social_user_info(id),
    UNIQUE INDEX(social_user_1, social_user_2)
);

insert into evolutions (name, description) values('7.sql', 'add social_connection table');

# --- !Downs
