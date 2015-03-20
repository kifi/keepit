# ELIZA

# --- !Ups

CREATE INDEX device_i_user_id ON device (user_id);

insert into evolutions (name, description) values('101.sql', 'fixing devices table indices');

DROP INDEX device_u_token ON device;
DROP INDEX device_u_user_id ON device;

# --- !Downs

