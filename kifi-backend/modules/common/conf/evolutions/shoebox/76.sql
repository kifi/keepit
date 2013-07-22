# --- !Ups

CREATE TABLE user_bookmark_clicks(
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    uri_id bigint(20) NOT NULL,
    self_clicks INT NOT NULL,
    other_clicks INT NOT NULL,
  	
  	PRIMARY KEY (id),
  	CONSTRAINT user_bookmark_user_id FOREIGN KEY (user_id) REFERENCES user(id),
  	CONSTRAINT user_bookmark_uri_id FOREIGN KEY (uri_id) REFERENCES normalized_uri(id),
	UNIQUE INDEX user_bookmark_clicks_index(user_id, uri_id)  	
);

insert into evolutions (name, description) values('76.sql', 'adding user_bookmark_clicks table');


# --- !Downs
