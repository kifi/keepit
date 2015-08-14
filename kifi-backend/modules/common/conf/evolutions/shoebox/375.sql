# SHOEBOX

# --- !Ups

CREATE TABLE payment_method (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,
    account_id bigint(20) NOT NULL,
    default boolean NULL,
    stripe_token varchar(1024) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE INDEX payment_method_u_account_id_default (account_id, default)
);


CREATE TABLE paid_account (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,
    org_id bigint(20) NOT NULL,
    plan_id bigint(20) NOT NULL,
    credit int(11) NOT NULL,
    user_contacts text NOT NULL,
    email_contacts text NOT NULL,
    PRIMARY KEY (id),
    UNIQUE INDEX paid_account_u_org_id (org_id),
    CONSTRAINT paid_account_f_organization FOREIGN KEY (org_id) REFERENCES organization(id)
);


CREATE TABLE paid_plan (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,
    name varchar(256) NOT NULL,
    billing_cycle int(11) NOT NULL,
    price_per_cycle_per_user int(11) NOT NULL,
    PRIMARY KEY (id)
);


CREATE TABLE account_event (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,
    event_group bigint(20) NOT NULL,
    event_time datetime NOT NULL,
    account_id bigint(20) NOT NULL,
    billing_related boolean NOT NULL,
    whodunnit bigint(20) NULL,
    who_dunnit_extra text NOT NULL,
    kifi_admin_involved bigint(20) NULL,
    event_type varchar(128) NOT NULL,
    event_type_extras text NOT NULL,
    credit_change int(11) NOT NULL,
    payment_method bigint(20) NULL,
    payment_charge int(11) NULL,
    memo text NULL,
    PRIMARY KEY (id),

    CONSTRAINT account_event_f_user1 FOREIGN KEY (whodunnit) REFERENCES user(id),
    CONSTRAINT account_event_f_user2 FOREIGN KEY (kifi_admin_involved) REFERENCES user(id)

);


alter table payment_method
    add CONSTRAINT payment_method_to_paid_account FOREIGN KEY (account_id) REFERENCES paid_account(id);

alter table paid_account
    add CONSTRAINT paid_account_to_paid_plan FOREIGN KEY (plan_id) REFERENCES paid_plan(id);

alter table account_event
    add CONSTRAINT account_event_to_paid_account FOREIGN KEY (account_id) REFERENCES paid_account(id);




insert into evolutions (name, description) values('375.sql', 'core payments tables');

# --- !Downs
