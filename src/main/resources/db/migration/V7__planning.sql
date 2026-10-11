-- M5: регулярные операции, их исполнения и бюджеты.

create table recurring_rule (
    id             uuid primary key default uuidv7(),
    name           text not null check (length(trim(name)) > 0),
    type           text not null check (type in ('EXPENSE','INCOME','TRANSFER')),
    account_id     uuid not null references account,
    to_account_id  uuid references account,
    category_id    uuid references category,
    amount         numeric(19,4) not null check (amount > 0),   -- ожидаемая сумма в валюте счёта
    to_amount      numeric(19,4) check (to_amount > 0),         -- для перевода между валютами
    payee          text,
    frequency      text not null check (frequency in ('MONTHLY','WEEKLY','YEARLY')),
    interval_count smallint not null default 1 check (interval_count between 1 and 24),
    day_of_month   smallint check (day_of_month between 1 and 31),  -- 31 = последний день месяца
    day_of_week    smallint check (day_of_week between 1 and 7),    -- 1 = понедельник
    starts_on      date not null,
    ends_on        date check (ends_on is null or ends_on >= starts_on),
    auto_post      boolean not null default false,
    tolerance_pct  numeric(5,2) not null default 10 check (tolerance_pct between 0 and 100),
    active         boolean not null default true,
    version        int not null default 0,
    created_at     timestamptz not null default now(),
    constraint recurring_transfer check ((type = 'TRANSFER') = (to_account_id is not null)),
    constraint recurring_category check ((type = 'TRANSFER') or category_id is not null)
);

-- Исполнения храним только решённые: проведено (со ссылкой на операцию) или пропущено.
-- Ожидаемые даты вычисляются из расписания.
create table planned_occurrence (
    id           uuid primary key default uuidv7(),
    rule_id      uuid not null references recurring_rule on delete cascade,
    due_on       date not null,
    status       text not null check (status in ('POSTED','SKIPPED')),
    operation_id uuid references operation on delete cascade,  -- удалили операцию — платёж снова ожидается
    resolved_at  timestamptz not null default now(),
    unique (rule_id, due_on)
);
create unique index planned_occurrence_operation on planned_occurrence (operation_id) where operation_id is not null;

-- Бюджет на категорию (вместе с подкатегориями) или подкатегорию на бюджетный месяц, в базовой валюте.
create table budget (
    id          uuid primary key default uuidv7(),
    category_id uuid not null references category on delete cascade,
    month       date not null check (extract(day from month) = 1),
    amount      numeric(19,4) not null check (amount >= 0),
    rollover    boolean not null default false,
    unique (category_id, month)
);

-- С какого числа начинается бюджетный месяц: например, с дня зарплаты.
alter table app_settings add column budget_month_start smallint not null default 1
    check (budget_month_start between 1 and 28);
