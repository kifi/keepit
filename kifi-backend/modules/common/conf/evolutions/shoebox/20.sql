# --- !Ups

CREATE TABLE kifi_installation (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,

    user_id bigint(20) NOT NULL,
    external_id varchar(38) NOT NULL,
    version varchar(16) NOT NULL,
    user_agent varchar(512) NOT NULL,

    KEY (user_id, external_id),
    PRIMARY KEY (id),

    CONSTRAINT kifi_installation_user_browser UNIQUE (user_id, external_id),
    CONSTRAINT kifi_installation_f_user FOREIGN KEY (user_id) REFERENCES user(id)
);

insert into evolutions (name, description) values('20.sql', 'create new table kifi_installation');

# --- !Downs

