-- M3: цели. Деньги на цель учитываются либо по реальным счетам (доля остатка), либо виртуальным
-- резервом — отметками «отложил», без отдельного счёта.

create table goal (
    id            uuid primary key default uuidv7(),
    name          text not null check (length(trim(name)) > 0),
    icon          text,
    kind          text not null default 'AMOUNT' check (kind in ('AMOUNT','MONTHS_OF_EXPENSES')),
    target_amount numeric(19,4) check (target_amount > 0),   -- для AMOUNT
    target_months smallint check (target_months between 1 and 120),  -- для MONTHS_OF_EXPENSES
    currency      char(3) not null references currency,
    target_date   date,
    funding       text not null default 'ACCOUNTS' check (funding in ('ACCOUNTS','MANUAL')),
    priority      int not null default 0,
    status        text not null default 'ACTIVE' check (status in ('ACTIVE','ACHIEVED','ARCHIVED')),
    version       int not null default 0,
    created_at    timestamptz not null default now(),
    achieved_at   timestamptz,
    constraint goal_target check (
        (kind = 'AMOUNT' and target_amount is not null)
        or (kind = 'MONTHS_OF_EXPENSES' and target_months is not null))
);

-- funding = ACCOUNTS: где лежат деньги цели и какая доля остатка относится к ней
create table goal_account (
    goal_id    uuid not null references goal on delete cascade,
    account_id uuid not null references account,
    share_pct  numeric(5,2) not null default 100 check (share_pct > 0 and share_pct <= 100),
    primary key (goal_id, account_id)
);

-- funding = MANUAL: отметки «отложил» и «взял»; сумма в валюте цели
create table goal_contribution (
    id      uuid primary key default uuidv7(),
    goal_id uuid not null references goal on delete cascade,
    made_on date not null,
    amount  numeric(19,4) not null check (amount <> 0),
    note    text,
    created_at timestamptz not null default now()
);

create index goal_contribution_goal_idx on goal_contribution (goal_id, made_on);
