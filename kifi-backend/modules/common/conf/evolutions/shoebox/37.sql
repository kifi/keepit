# --- !Ups

CREATE TABLE user_to_domain (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    user_id bigint(20) NOT NULL,
    domain_id bigint(20) NOT NULL,
    kind varchar(32) NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT user_to_domain__user_id FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT user_to_domain__domain_id FOREIGN KEY (domain_id) REFERENCES domain(id),
    UNIQUE INDEX user_to_domain__user_id_domain_id_kind (user_id, domain_id, kind));

insert into evolutions (name, description) values('37.sql', 'adding user_to_domain table');

# --- !Downs
