# --- !Ups


CREATE TABLE invitation (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    sender_user_id  bigint(20) NOT NULL,
    recipient_social_user_id bigint(20) NOT NULL,
    state varchar(20) NOT NULL,
    
    PRIMARY KEY (id),

    UNIQUE invitation_i_recipient_social_user_id (recipient_social_user_id),

    CONSTRAINT invitation_sender_user_id FOREIGN KEY (sender_user_id) REFERENCES user(id),
    CONSTRAINT invitation_recipient_social_user_id FOREIGN KEY (recipient_social_user_id) REFERENCES social_user_info(id)
);

    
insert into evolutions (name, description) values('53.sql', 'added invitation table');

# --- !Downs
