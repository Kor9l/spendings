-- M1: счета, категории, операции.

create extension if not exists pg_trgm;  -- нечёткий поиск и подсказки по получателю

create table account (
    id              uuid primary key default uuidv7(),
    name            text not null check (length(trim(name)) > 0),
    type            text not null check (type in ('CASH','CARD','SAVINGS','DEPOSIT','CREDIT','OTHER')),
    currency        char(3) not null references currency,
    opening_balance numeric(19,4) not null default 0,
    opened_on       date not null default current_date,
    in_net_worth    boolean not null default true,
    sort_order      int not null default 0,
    archived_at     timestamptz,
    version         int not null default 0,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create table deposit_terms (
    account_id        uuid primary key references account on delete cascade,
    bank              text,
    annual_rate       numeric(7,4) not null check (annual_rate >= 0),   -- 12.5000 = 12,5 % годовых
    capitalization    text not null check (capitalization in ('NONE','MONTHLY','QUARTERLY','AT_MATURITY')),
    payout_account_id uuid references account,
    tax_rate          numeric(5,2) not null default 0 check (tax_rate between 0 and 100),
    opened_on         date not null,
    matures_on        date check (matures_on is null or matures_on > opened_on),
    revocable         boolean not null default true,
    auto_renew        boolean not null default false
);

create table category (
    id          uuid primary key default uuidv7(),
    parent_id   uuid references category,
    kind        text not null check (kind in ('EXPENSE','INCOME')),
    name        text not null check (length(trim(name)) > 0),
    icon        text,
    color       text check (color is null or color ~ '^#[0-9a-fA-F]{6}$'),
    sort_order  int not null default 0,
    archived_at timestamptz,
    constraint category_name_unique unique nulls not distinct (parent_id, kind, name),
    constraint category_not_own_parent check (parent_id is null or parent_id <> id)
);
-- глубину не больше 2 и одинаковый kind у родителя и потомка проверяет сервис

create index category_parent_idx on category (parent_id);

create table operation (
    id           uuid primary key default uuidv7(),
    type         text not null check (type in ('EXPENSE','INCOME','TRANSFER','ADJUSTMENT')),
    occurred_on  date not null,
    payee        text,
    note         text,
    source       text not null default 'MANUAL' check (source in ('MANUAL','IMPORT','RECURRING','BOT')),
    status       text not null default 'CLEARED' check (status in ('CLEARED','REVIEW')),
    import_id    uuid,
    external_key text unique,
    version      int not null default 0,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

create table operation_line (
    id                uuid primary key default uuidv7(),
    operation_id      uuid not null references operation on delete cascade,
    position          smallint not null default 0,
    account_id        uuid not null references account,
    category_id       uuid references category,
    amount            numeric(19,4) not null check (amount <> 0),   -- «−» списание, «+» поступление, в валюте счёта
    original_amount   numeric(19,4) check (original_amount is null or original_amount > 0),
    original_currency char(3) references currency,
    note              text,
    constraint operation_line_original_pair
        check ((original_amount is null) = (original_currency is null))
);

create index operation_feed_idx on operation (occurred_on desc, created_at desc);
create index operation_line_operation_idx on operation_line (operation_id);
create index operation_line_account_idx on operation_line (account_id);
create index operation_line_category_idx on operation_line (category_id);
create index operation_payee_trgm on operation using gin (payee gin_trgm_ops);
