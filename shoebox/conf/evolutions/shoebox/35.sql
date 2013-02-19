# --- !Ups

CREATE TABLE comment_read (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    user_id bigint(20) NOT NULL,
    uri_id bigint(20) NOT NULL,
    parent_id bigint(20),
    last_read_id bigint(20) NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT comment_read_user_id FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT comment_read_uri_id FOREIGN KEY (uri_id) REFERENCES normalized_uri(id),
    CONSTRAINT comment_read_parent_id FOREIGN KEY (parent_id) REFERENCES comment(id),
    CONSTRAINT comment_read_last_read_id FOREIGN KEY (last_read_id) REFERENCES comment(id),

    INDEX comment_read_i_message (user_id,parent_id,state),
    INDEX comment_read_i_comment (user_id,uri_id,state,parent_id)
);

insert into evolutions (name, description) values('35.sql', 'adding comment_read table');

# --- !Downs

