# ELIZA

# --- !Ups


CREATE INDEX message_i_uri_id ON message (sent_on_uri_id);
CREATE INDEX user_thread_i_uri_id ON user_thread (uri_id);

insert into evolutions (name, description) values('134.sql', 'adding additional uri_id indices on eliza');

# --- !Downs
