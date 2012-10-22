# --- !Ups

CREATE TABLE social_user_info (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,

    user_id bigint(20),
    full_name varchar(512) NOT NULL,
    social_id varchar(32) NOT NULL,
    network_type varchar(32) NOT NULL,
    credentials varchar(2048),
    
    KEY (social_id, network_type),
    KEY (user_id),
    PRIMARY KEY (id),
    
    CONSTRAINT social_user_info_f_user FOREIGN KEY (user_id) REFERENCES user(id), 
    CONSTRAINT social_user_info_u_social_id_natwork_type UNIQUE KEY (social_id, network_type) 
);

alter table user
    drop column social_user;

alter table user
    drop column facebook_id;

insert into evolutions (name, description) values('5.sql', 'adding social_user_info');

# --- !Downs
