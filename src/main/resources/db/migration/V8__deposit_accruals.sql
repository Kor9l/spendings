-- M6: начисления процентов по вкладам. Ожидаемые периоды вычисляются из условий вклада,
-- здесь хранятся только решённые: проведено (со ссылкой на операцию дохода) или пропущено.
create table deposit_accrual (
    id           uuid primary key default uuidv7(),
    account_id   uuid not null references account on delete cascade,
    period_end   date not null,
    status       text not null check (status in ('POSTED','SKIPPED')),
    operation_id uuid references operation on delete cascade,  -- удалили доход — начисление снова ожидается
    resolved_at  timestamptz not null default now(),
    unique (account_id, period_end)
);
create unique index deposit_accrual_operation on deposit_accrual (operation_id) where operation_id is not null;

-- проценты по вкладу — отдельный источник: темп накопления целей их не учитывает, их учитывает ставка
alter table operation drop constraint operation_source_check;
alter table operation add constraint operation_source_check
    check (source in ('MANUAL','IMPORT','RECURRING','BOT','INTEREST'));
