// Графики ECharts. Сервер кладёт данные в data-chart как JSON, здесь строится оформление.
// Цвета текста и осей берутся из темы Bootstrap, чтобы графики читались и в тёмной теме.
(() => {
  const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const money = (value, currency) =>
    `${Number(value).toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`;

  const builders = {
    sunburst(data, currency) {
      return {
        tooltip: { formatter: (p) => `${p.treePathInfo.slice(1).map((n) => n.name).join(' › ')}<br>${money(p.value, currency)}` },
        series: [{
          type: 'sunburst',
          data: data.data,
          radius: ['18%', '95%'],
          sort: null,
          itemStyle: { borderColor: css('--bs-body-bg'), borderWidth: 2 },
          label: { color: css('--bs-body-color'), minAngle: 12, overflow: 'truncate', width: 80 },
          levels: [{}, { label: { rotate: 'tangential' } }, { label: { rotate: 'radial', fontSize: 11 }, itemStyle: { opacity: 0.8 } }],
        }],
      };
    },

    trend(data, currency) {
      const axis = { axisLabel: { color: css('--bs-secondary-color') }, axisLine: { lineStyle: { color: css('--bs-border-color') } } };
      return {
        tooltip: { trigger: 'axis', valueFormatter: (v) => money(v, currency) },
        legend: { top: 0, textStyle: { color: css('--bs-body-color') } },
        grid: { left: 8, right: 8, top: 36, bottom: 8, containLabel: true },
        xAxis: { type: 'category', data: data.months.map((m) => m.slice(5) + '.' + m.slice(2, 4)), ...axis },
        yAxis: {
          type: 'value',
          axisLine: axis.axisLine,
          axisLabel: { color: css('--bs-secondary-color'), formatter: (v) => v.toLocaleString('ru-RU') },
          splitLine: { lineStyle: { color: css('--bs-border-color-translucent') } },
        },
        series: [
          { name: 'Доходы', type: 'bar', data: data.income, itemStyle: { color: css('--bs-success') } },
          { name: 'Расходы', type: 'bar', data: data.expenses, itemStyle: { color: css('--bs-danger') } },
        ],
      };
    },

    capital(data, currency) {
      const axis = { axisLabel: { color: css('--bs-secondary-color') }, axisLine: { lineStyle: { color: css('--bs-border-color') } } };
      return {
        tooltip: { trigger: 'axis', valueFormatter: (v) => money(v, currency) },
        legend: { top: 0, textStyle: { color: css('--bs-body-color') } },
        grid: { left: 8, right: 8, top: 36, bottom: 8, containLabel: true },
        xAxis: { type: 'category', boundaryGap: false, data: data.months.map((m) => m.slice(5) + '.' + m.slice(2, 4)), ...axis },
        yAxis: {
          type: 'value',
          axisLine: axis.axisLine,
          axisLabel: { color: css('--bs-secondary-color'), formatter: (v) => v.toLocaleString('ru-RU') },
          splitLine: { lineStyle: { color: css('--bs-border-color-translucent') } },
        },
        series: [
          { name: 'Капитал', type: 'line', data: data.total, smooth: true, areaStyle: { opacity: 0.15 }, itemStyle: { color: css('--bs-primary') } },
          { name: 'Вклады и накопления', type: 'line', data: data.savings, smooth: true, itemStyle: { color: css('--bs-success') } },
        ],
      };
    },

    interest(data, currency) {
      const axis = { axisLabel: { color: css('--bs-secondary-color') }, axisLine: { lineStyle: { color: css('--bs-border-color') } } };
      return {
        tooltip: { trigger: 'axis', valueFormatter: (v) => money(v, currency) },
        legend: { top: 0, textStyle: { color: css('--bs-body-color') } },
        grid: { left: 8, right: 8, top: 36, bottom: 8, containLabel: true },
        xAxis: { type: 'category', data: data.months, ...axis },
        yAxis: {
          type: 'value',
          axisLine: axis.axisLine,
          axisLabel: { color: css('--bs-secondary-color'), formatter: (v) => v.toLocaleString('ru-RU') },
          splitLine: { lineStyle: { color: css('--bs-border-color-translucent') } },
        },
        series: data.series.map((s) => ({ name: s.name, type: 'bar', stack: 'interest', data: s.data })),
      };
    },

    sankey(data, currency) {
      return {
        tooltip: { trigger: 'item', valueFormatter: (v) => money(v, currency) },
        series: [{
          type: 'sankey',
          left: 8, right: 170, top: 8, bottom: 8,
          nodeGap: 10,
          emphasis: { focus: 'adjacency' },
          data: data.nodes.map((n) => ({ name: n.name, itemStyle: { color: n.color } })),
          links: data.links,
          lineStyle: { color: 'gradient', opacity: 0.35 },
          label: { color: css('--bs-body-color') },
        }],
      };
    },
  };

  function render(root) {
    if (!window.echarts) return;
    root.querySelectorAll('[data-chart]').forEach((el) => {
      const data = JSON.parse(el.dataset.chart);
      const build = builders[data.type];
      if (!build) return;
      echarts.getInstanceByDom(el)?.dispose();
      const chart = echarts.init(el, null, { renderer: 'svg' });
      chart.setOption(build(data, el.dataset.currency || ''));
    });
  }

  const resizeAll = () => document.querySelectorAll('[data-chart]').forEach((el) => echarts.getInstanceByDom(el)?.resize());

  document.addEventListener('DOMContentLoaded', () => render(document));
  document.addEventListener('htmx:afterSettle', (e) => render(e.target));
  window.addEventListener('resize', resizeAll);
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => setTimeout(() => render(document), 50));
})();
