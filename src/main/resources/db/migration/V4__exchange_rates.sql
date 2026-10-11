-- M2: курсы валют. Курс хранится к валюте провайдера (pivot, у НБРБ — BYN) за 1 единицу:
-- base = USD, quote = BYN, rate = 3.0051 значит 1 USD = 3,0051 BYN. Кросс-курсы считаются через pivot.

create table exchange_rate (
    rate_date date not null,
    base      char(3) not null references currency,
    quote     char(3) not null references currency,
    rate      numeric(20,10) not null check (rate > 0),
    source    text not null check (source in ('NBRB','MANUAL')),
    primary key (base, quote, rate_date)
);

-- Курс валюты к pivot на дату: ближайший не позже даты. Для самой pivot — 1. Нет курса — null.
create function fx_to_pivot(cur char(3), on_date date) returns numeric
    language sql stable
as $$
    select case
        when cur = 'BYN' then 1::numeric
        else (select r.rate from exchange_rate r
              where r.base = cur and r.quote = 'BYN' and r.rate_date <= on_date
              order by r.rate_date desc
              limit 1)
    end
$$;

-- Сколько единиц to_cur стоит 1 единица from_cur на дату. Нет курса — null, а не 1.
create function fx_rate(from_cur char(3), to_cur char(3), on_date date) returns numeric
    language sql stable
as $$
    select case
        when from_cur = to_cur then 1::numeric
        else fx_to_pivot(from_cur, on_date) / nullif(fx_to_pivot(to_cur, on_date), 0)
    end
$$;
