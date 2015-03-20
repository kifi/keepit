# ELIZA

# --- !Ups

CREATE INDEX device_i_user_id ON device (user_id);

insert into evolutions (name, description) values('101.sql', 'fixing devices table indices');

# --- !Downs

