# SHOEBOX

# --- !Ups

CREATE INDEX user_ip_addresses_i_seq ON user_ip_addresses(seq);

insert into evolutions (name, description) values('382.sql', 'add index on user_ip_addresses seq');

# --- !Downs
