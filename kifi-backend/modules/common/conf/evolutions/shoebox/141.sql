# SHOEBOX

# --- !Ups


ALTER TABLE social_connection add seq Int NOT NULL DEFAULT 0;
CREATE INDEX social_connection_i_seq ON social_connection(seq);
UPDATE social_connection SET seq=id;


ALTER TABLE social_user_info add seq Int NOT NULL DEFAULT 0;
CREATE INDEX social_user_info_i_seq ON social_user_info(seq);
UPDATE social_user_info SET seq=id;



ALTER TABLE invitation add seq Int NOT NULL DEFAULT 0;
CREATE INDEX invitation_i_seq ON invitation(seq);
UPDATE invitation SET seq=id;

INSERT INTO evolutions (name, description) VALUES ('141.sql', 'add sequence number to social_connection, social_user_info and invitation');

# --- !Downs
