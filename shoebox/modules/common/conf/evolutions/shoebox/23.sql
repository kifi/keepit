# --- !Ups

ALTER TABLE comment ADD CONSTRAINT comment_parent FOREIGN KEY (parent) REFERENCES comment(id);
ALTER TABLE comment ADD CONSTRAINT comment_normalized_uri_id FOREIGN KEY (normalized_uri_id) REFERENCES normalized_uri(id);

CREATE INDEX comment_permissions ON comment(permissions);
CREATE INDEX comment_state ON comment(state);
    
insert into evolutions (name, description) values('23.sql', 'added comment indexes');

# --- !Downs
