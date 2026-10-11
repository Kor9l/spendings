-- Шаблон категорий. Его можно править в интерфейсе как угодно: переименовать, архивировать, объединить.
-- Подкатегории без своей иконки и цвета берут их у родителя.

with parents (kind, name, icon, color, sort_order, children) as (
    values
        ('EXPENSE', 'Еда',                 '🍽️', '#e07a5f',  1, array['Продукты', 'Кафе и рестораны', 'Доставка']),
        ('EXPENSE', 'Жильё и коммуналка',  '🏠', '#3d5a80',  2, array['Электроэнергия', 'Газ', 'Вода', 'Отопление', 'Интернет и ТВ', 'Обслуживание дома']),
        ('EXPENSE', 'Транспорт',           '🚗', '#4f8a6e',  3, array['Топливо', 'Парковка', 'ТО и ремонт', 'Страховка', 'Общественный транспорт', 'Такси']),
        ('EXPENSE', 'Здоровье',            '💊', '#d1495b',  4, array['Аптека', 'Врачи', 'Анализы']),
        ('EXPENSE', 'Дом',                 '🛋️', '#a8763e',  5, array['Бытовая химия', 'Мебель и техника', 'Ремонт']),
        ('EXPENSE', 'Связь и подписки',    '📱', '#6d597a',  6, array['Мобильная связь', 'Подписки']),
        ('EXPENSE', 'Одежда и обувь',      '👕', '#b56576',  7, array[]::text[]),
        ('EXPENSE', 'Отдых и развлечения', '🎉', '#d4a017',  8, array['Путешествия', 'Хобби', 'Кино и театр']),
        ('EXPENSE', 'Подарки',             '🎁', '#ee6c4d',  9, array[]::text[]),
        ('EXPENSE', 'Образование',         '📚', '#457b9d', 10, array[]::text[]),
        ('EXPENSE', 'Финансы',             '🏦', '#6c757d', 11, array['Комиссии банка', 'Проценты по кредитам', 'Налоги']),
        ('INCOME',  'Зарплата',            '💼', '#2a9d8f',  1, array['Аванс', 'Основная часть', 'Премия']),
        ('INCOME',  'Проценты по вкладам', '📈', '#588157',  2, array[]::text[]),
        ('INCOME',  'Подработка',          '🛠️', '#c9a227',  3, array[]::text[]),
        ('INCOME',  'Кэшбэк и бонусы',     '💳', '#6a994e',  4, array[]::text[]),
        ('INCOME',  'Подарки',             '🎁', '#e76f51',  5, array[]::text[]),
        ('INCOME',  'Прочие доходы',       '➕', '#6c757d',  6, array[]::text[])
),
inserted as (
    insert into category (kind, name, icon, color, sort_order)
    select kind, name, icon, color, sort_order from parents
    returning id, kind, name
)
insert into category (parent_id, kind, name, sort_order)
select i.id, p.kind, child.name, child.ord
from inserted i
join parents p on p.kind = i.kind and p.name = i.name
cross join lateral unnest(p.children) with ordinality as child(name, ord);
