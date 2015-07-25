# Shoebox

# --- !Ups

CREATE INDEX user_ip_addresses_i_ip_address_created_at ON user_ip_addresses(ip_address, created_at);

insert into evolutions (name, description) values('342.sql', 'adding index on ip_address-created_at');

# --- !Downs
