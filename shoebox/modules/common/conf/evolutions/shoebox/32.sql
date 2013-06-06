# --- !Ups

CREATE TABLE slider_rule (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    group_name varchar(32) NOT NULL,
    name varchar(32) NOT NULL,
    parameters varchar(256),
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX slider_rule_group_name_index ON slider_rule (group_name);
CREATE INDEX slider_rule_name_index ON slider_rule (name);

INSERT INTO slider_rule (group_name, name, parameters, state, created_at, updated_at) VALUES
    ('default', 'message', null, 'active', now(), now()),
    ('default', 'comment', '["friend"]', 'active', now(), now()),
    ('default', 'sensitive', null, 'active', now(), now()),
    ('default', 'url', null, 'active', now(), now()),
    ('default', 'shown', null, 'active', now(), now()),
    ('default', 'scroll', '[2,80]', 'active', now(), now()),
    ('default', 'focus', '[30]', 'active', now(), now());

INSERT INTO evolutions (name, description) VALUES ('32.sql', 'adding slider rule tables');

# --- !Downs
