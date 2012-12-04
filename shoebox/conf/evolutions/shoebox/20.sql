# --- !Ups

CREATE TABLE extension_version (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,

    user_id bigint(20) NOT NULL,
    version varchar(16) NOT NULL,
    browser_instance_id varchar(38) NOT NULL,
    user_agent varchar(512) NOT NULL,
    
    KEY (user_id, browser_instance_id),
    PRIMARY KEY (id),
    
    CONSTRAINT extension_version_user_browser UNIQUE (user_id, browser_instance_id),
    CONSTRAINT extension_version_f_user FOREIGN KEY (user_id) REFERENCES user(id) 
);
             
insert into evolutions (name, description) values('20.sql', 'create new table extension_version');

# --- !Downs
