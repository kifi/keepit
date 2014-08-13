alter table raw_seed_item
    add column url NOT NULL;

insert into evolutions (name, description) values('227.sql', 'add url column');