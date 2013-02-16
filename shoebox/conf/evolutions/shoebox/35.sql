# --- !Ups

CREATE INDEX slider_rule_name_index ON slider_rule (name);

INSERT INTO evolutions (name, description) VALUES ('35.sql', 'adding name index for slider_rule');

# --- !Downs
