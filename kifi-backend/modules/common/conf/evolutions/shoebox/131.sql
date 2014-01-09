# SHOEBOX

# --- !Ups

CREATE INDEX social_user_info_i_network_type ON social_user_info(network_type);

insert into evolutions (name, description) values('131.sql', 'indexing social user info on network type');

# --- !Downs
