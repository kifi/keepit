# SHOEBOX

ALTER TABLE invitation DROP INDEX invitation_i_recipient_social_user_id;
ALTER TABLE invitation DROP FOREIGN KEY invitation_recipient_social_user_id;
ALTER TABLE invitation MODIFY recipient_social_user_id bigint(20);

INSERT INTO evolutions (name, description) VALUES ('121.sql', 'make recipient_social_user_id optional');

# -- !Downs
