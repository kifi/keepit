# SHOEBOX

# --- !Ups

alter TABLE organization_membership
    delete column access;

alter TABLE organization_membership
    add column role varchar(20) NOT NULL;

alter TABLE organization_membership
    add column permissions varchar(120) NOT NULL;



alter TABLE organization_invite
    delete column access;

alter TABLE organization_invite
    add column role varchar(20) NOT NULL;


insert into evolutions (name, description) values('342.sql', 'changing to permissions-based membership');

# --- !Downs
