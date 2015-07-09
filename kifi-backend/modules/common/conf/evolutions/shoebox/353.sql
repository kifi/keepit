# ROVER

# --- !Ups
CREATE TABLE if not exists rover_url_rule (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    pattern varchar(2048) NOT NULL,
    proxy_id bigint(20) NULL,

    PRIMARY KEY (id),
    CONSTRAINT rover_url_rule_proxy FOREIGN KEY (proxy_id) REFERENCES rover_http_proxy(id)
);

insert into evolutions (name, description) values('353.sql', 'create rover_url_rule table');

# --- !Downs
