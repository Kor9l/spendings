-- M4: импорт выписок, «Входящие», правила автокатегоризации.

-- Профиль банка: как читать его выписку. Номера колонок — с нуля; null — колонки нет.
create table import_profile (
    id                      uuid primary key default uuidv7(),
    name                    text not null unique check (length(trim(name)) > 0),
    account_id              uuid references account on delete set null,
    encoding                text not null default 'UTF-8' check (encoding in ('UTF-8','windows-1251')),
    delimiter               text not null default ';' check (length(delimiter) = 1),
    header_row              int not null default 0 check (header_row >= 0),
    date_column             int not null,
    date_format             text not null default 'dd.MM.yyyy',
    amount_column           int,
    debit_column            int,
    credit_column           int,
    invert_sign             boolean not null default false,
    description_column      int,
    original_amount_column  int,
    original_currency_column int,
    created_at              timestamptz not null default now(),
    constraint import_profile_amount check (amount_column is not null or debit_column is not null or credit_column is not null)
);

create table import_batch (
    id              uuid primary key default uuidv7(),
    account_id      uuid not null references account,
    profile_id      uuid references import_profile on delete set null,
    file_name       text not null,
    rows_total      int not null default 0,
    rows_imported   int not null default 0,
    rows_skipped    int not null default 0,
    imported_at     timestamptz not null default now()
);

alter table operation
    add constraint operation_import_fk foreign key (import_id) references import_batch on delete set null;
create index operation_review_idx on operation (status) where status = 'REVIEW';
create index operation_import_idx on operation (import_id);

-- «описание содержит pattern → категория». Сравнение без учёта регистра.
create table categorization_rule (
    id          uuid primary key default uuidv7(),
    pattern     text not null check (length(trim(pattern)) > 0),
    category_id uuid not null references category on delete cascade,
    kind        text not null check (kind in ('EXPENSE','INCOME')),
    created_at  timestamptz not null default now()
);
create unique index categorization_rule_unique on categorization_rule (lower(pattern), kind);

-- Куда попадает импортированное, пока категорию не выбрали.
insert into category (kind, name, icon, color, sort_order)
values ('EXPENSE', 'Не разобрано', '❓', '#adb5bd', 100),
       ('INCOME',  'Не разобрано', '❓', '#adb5bd', 100);
