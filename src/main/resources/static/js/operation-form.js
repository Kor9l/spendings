// Состояние формы операции (Alpine.js). Начальное состояние сервер кладёт в data-state как JSON.
function operationForm(stateJson) {
  const s = typeof stateJson === 'string' ? JSON.parse(stateJson) : stateJson;
  const emptyRow = () => ({ categoryId: '', amount: '', note: '' });

  return {
    type: s.type,
    top: s.top,
    cat: s.cat,
    split: s.split,
    splits: s.splits.length ? s.splits : [emptyRow(), emptyRow()],
    accountId: s.accountId,
    toAccountId: s.toAccountId,
    currencies: s.currencies,
    showOriginal: s.showOriginal,

    get kind() {
      return this.type === 'INCOME' ? 'INCOME' : 'EXPENSE';
    },
    get categorized() {
      return this.type === 'EXPENSE' || this.type === 'INCOME';
    },
    get crossCurrency() {
      return (
        this.type === 'TRANSFER' &&
        this.toAccountId !== '' &&
        this.currencies[this.accountId] !== this.currencies[this.toAccountId]
      );
    },
    currencyOf(accountId) {
      return this.currencies[accountId] ?? '';
    },

    setType(type) {
      if (type === this.type) return;
      this.type = type;
      this.top = '';
      this.cat = '';
    },
    pickTop(id) {
      this.top = id;
      this.cat = id;
    },
    pickSub(id) {
      this.cat = id;
    },

    addRow() {
      this.splits.push(emptyRow());
    },
    removeRow(index) {
      this.splits.splice(index, 1);
    },
    get splitTotal() {
      const total = this.splits.reduce((sum, row) => sum + parseAmount(row.amount), 0);
      return total.toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    },

    // Получатель запоминает категорию: подставляем её, если категория ещё не выбрана.
    async suggestCategory(payee) {
      if (!payee || this.cat || !this.categorized || this.split) return;
      const params = new URLSearchParams({ payee, type: this.type });
      const response = await fetch(`/operations/payee-category?${params}`);
      if (response.status !== 200) return;
      const { categoryId } = await response.json();
      const chip = document.querySelector(`[data-cat="${categoryId}"]`);
      this.top = chip?.dataset.top ?? categoryId;
      this.cat = categoryId;
    },
  };
}

function parseAmount(text) {
  if (!text) return 0;
  let s = String(text).replace(/[\s  ']/g, '');
  const decimal = Math.max(s.lastIndexOf(','), s.lastIndexOf('.'));
  if (decimal >= 0) {
    s = s.slice(0, decimal).replace(/[.,]/g, '') + '.' + s.slice(decimal + 1);
  }
  const value = Number.parseFloat(s);
  return Number.isFinite(value) ? value : 0;
}
