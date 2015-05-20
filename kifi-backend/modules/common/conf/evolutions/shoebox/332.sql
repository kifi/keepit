# SHOEBOX

# --- !Ups

alter table email_opt_out
    add index email_opt_out_i_address (address);

insert into evolutions (name, description) values('332.sql', 'add index in email_opt_out for address');

# --- !Downs
