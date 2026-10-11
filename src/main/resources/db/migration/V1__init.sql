-- M0: справочник валют, настройки и единственный пользователь.
-- Счета, категории и операции появятся в миграциях этапа M1.

create table currency (
    code        char(3)  primary key,            -- ISO 4217
    name        text     not null,
    minor_units smallint not null default 2 check (minor_units between 0 and 4)
);

insert into currency (code, name, minor_units) values
    ('BYN', 'Белорусский рубль', 2),
    ('USD', 'Доллар США',        2),
    ('EUR', 'Евро',              2),
    ('RUB', 'Российский рубль',  2);

-- Ровно одна строка: первичный ключ boolean, который может быть только true
create table app_settings (
    id            boolean primary key default true check (id),
    base_currency char(3) not null references currency
);

insert into app_settings (base_currency) values ('BYN');

create table app_user (
    id            uuid primary key default uuidv7(),
    username      text not null unique,
    password_hash text not null,
    singleton     boolean not null default true unique check (singleton),  -- второго пользователя не будет
    created_at    timestamptz not null default now()
);
