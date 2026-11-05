/*
 * Интерфейс модуля Incident advisor. Без сборки и зависимостей — обычный JS.
 * Данные идут через прокси-действия модуля в Java-сервис:
 *   GET  zabbix.php?action=incidentadvisor.api.get&path=/api/...
 *   POST zabbix.php?action=incidentadvisor.api.post  (path, body, _csrf_token)
 *
 * Автообновление не мешает работать: список и карточка перерисовываются, только если данные изменились;
 * раскрытые блоки и прокрутка сохраняются, форма оценки не перерисовывается вовсе — набранный текст
 * не пропадает. На скрытой вкладке опрос не идёт.
 */
(function () {
	'use strict';

	const SEVERITY = ['Не классифицировано', 'Информация', 'Предупреждение', 'Средняя', 'Высокая', 'Чрезвычайная'];
	const POLL_MS = 10000;
	const STATUS = {OPEN: ['Открыт', 'bad'], RESOLVED: ['Закрыт', 'ok']};
	const ANALYSIS = {
		PENDING: ['В очереди', 'muted'], RUNNING: ['Идёт разбор', 'info'],
		DONE: ['Разобран', 'ok'], FAILED: ['Ошибка разбора', 'bad']
	};
	const FILTERS = [['all', 'Все'], ['OPEN', 'Открытые'], ['RESOLVED', 'Закрытые']];
	const SVG_NS = 'http://www.w3.org/2000/svg';

	// ---------- помощники ----------

	function el(tag, attrs, ...children) {
		const node = document.createElement(tag);
		Object.entries(attrs || {}).forEach(([k, v]) => {
			if (v === null || v === undefined || v === false) return;
			if (k === 'class') node.className = v;
			else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
			else node.setAttribute(k, v === true ? '' : v);
		});
		children.flat(Infinity).forEach(c => {
			if (c === null || c === undefined || c === false || c === '') return;
			node.append(c instanceof Node ? c : document.createTextNode(String(c)));
		});
		return node;
	}

	function svg(tag, attrs, text) {
		const node = document.createElementNS(SVG_NS, tag);
		Object.entries(attrs || {}).forEach(([k, v]) => node.setAttribute(k, v));
		if (text !== undefined) node.textContent = text;
		return node;
	}

	const ms = iso => iso ? new Date(iso).getTime() : null;

	function fmtTime(iso) {
		if (!iso) return '—';
		const d = new Date(iso), now = new Date();
		const time = d.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit', second: '2-digit'});
		return d.toDateString() === now.toDateString()
			? time
			: d.toLocaleDateString([], {day: '2-digit', month: '2-digit'}) + ' ' + time;
	}

	function fmtSpan(millis) {
		const s = Math.round(Math.abs(millis) / 1000);
		if (s < 60) return `${s} с`;
		const m = Math.floor(s / 60);
		if (m < 60) return s % 60 ? `${m} мин ${s % 60} с` : `${m} мин`;
		const h = Math.floor(m / 60);
		if (h < 24) return m % 60 ? `${h} ч ${m % 60} мин` : `${h} ч`;
		return `${Math.floor(h / 24)} д ${h % 24} ч`;
	}

	function fmtAgo(iso) {
		if (!iso) return '';
		const diff = Date.now() - ms(iso);
		return diff < 10000 ? 'только что' : fmtSpan(diff) + ' назад';
	}

	/** Смещение от начала инцидента: «−2 мин», «+15 с», «в начале». */
	function fmtOffset(millis) {
		if (Math.abs(millis) < 1000) return 'в начале';
		return (millis < 0 ? '−' : '+') + fmtSpan(millis);
	}

	function fmtNumber(v) {
		if (v === null || v === undefined || Number.isNaN(v)) return '—';
		const a = Math.abs(v);
		if (a >= 1000) return Math.round(v).toLocaleString();
		if (a >= 100) return String(Math.round(v));
		if (a >= 1) return Number(v).toFixed(2).replace(/\.?0+$/, '');
		return Number(v).toFixed(3).replace(/\.?0+$/, '') || '0';
	}

	const pill = (text, tone, title) => el('span', {class: `ia-pill ia-pill-${tone}`, title}, text);
	const sevPill = s => el('span', {class: `ia-sev ia-sev-${s}`}, SEVERITY[s] || s);
	const hostChip = h => el('span', {class: 'ia-chip', title: h.name && h.name !== h.host ? h.name : null}, h.host);
	/** Относительное время, которое обновляется само без перерисовки. */
	const ago = iso => el('span', {class: 'ia-ago', 'data-ts': iso, title: iso ? new Date(iso).toLocaleString() : null},
		fmtAgo(iso));

	/** Длительность: у открытого инцидента идёт сама, без перерисовки. */
	const duration = (fromIso, toIso) => toIso
		? el('span', {}, fmtSpan(ms(toIso) - ms(fromIso)))
		: el('span', {class: 'ia-dur', 'data-from': fromIso}, fmtSpan(Date.now() - ms(fromIso)));

	// ---------- API ----------

	class Api {
		constructor(csrf) {
			this.csrf = csrf;
		}

		async get(path) {
			const res = await fetch('zabbix.php?action=incidentadvisor.api.get&path=' + encodeURIComponent(path),
				{credentials: 'same-origin'});
			return this.parse(res);
		}

		async post(path, body) {
			const form = new FormData();
			form.append('path', path);
			form.append('body', JSON.stringify(body || {}));
			form.append('_csrf_token', this.csrf);
			const res = await fetch('zabbix.php?action=incidentadvisor.api.post',
				{method: 'POST', body: form, credentials: 'same-origin'});
			return this.parse(res);
		}

		async parse(res) {
			const text = await res.text();
			let data = null;
			try {
				data = text ? JSON.parse(text) : null;
			} catch (e) {
				throw new Error(res.ok ? 'Ответ не JSON' : res.status + ' ' + res.statusText);
			}
			if (!res.ok) throw new Error((data && data.error) || res.status + ' ' + res.statusText);
			return data;
		}
	}

	// ---------- приложение ----------

	class App {
		constructor(root) {
			this.root = root;
			this.api = new Api(root.dataset.csrf);
			this.canWrite = root.dataset.canWrite === '1';
			this.incidents = [];
			this.filter = 'all';
			this.query = '';
			this.selected = new URLSearchParams(location.hash.slice(1)).get('incident');
			this.paused = false;
			this.listKey = null;
			this.cardKey = null;
			this.detail = null;
			this.updatedAt = null;

			this.info = el('div', {class: 'ia-info'}, el('span', {class: 'ia-muted'}, 'Подключаюсь к сервису…'));
			this.liveText = el('span', {class: 'ia-live-text'}, 'загрузка');
			this.liveBtn = el('button', {type: 'button', class: 'ia-live', title: 'Автообновление каждые 10 с',
				onclick: () => this.togglePause()}, el('span', {class: 'ia-live-dot'}), this.liveText);
			this.refreshBtn = el('button', {type: 'button', class: 'ia-btn ia-btn-ghost ia-btn-sm', title: 'Обновить сейчас',
				onclick: () => this.refresh(true)}, '↻ Обновить');
			this.banner = el('div', {class: 'ia-banner', hidden: true});
			this.stats = el('div', {class: 'ia-stats'});

			this.search = el('input', {type: 'search', class: 'ia-field ia-search', placeholder: 'Поиск: триггер, хост, источник',
				'aria-label': 'Поиск инцидентов', oninput: () => { this.query = this.search.value.trim().toLowerCase(); this.renderList(true); }});
			this.filters = el('div', {class: 'ia-segmented', role: 'tablist'}, FILTERS.map(([key, label]) =>
				el('button', {type: 'button', role: 'tab', 'data-filter': key, class: key === this.filter ? 'ia-on' : null,
					onclick: () => this.setFilter(key)}, label)));
			this.list = el('div', {class: 'ia-list', tabindex: '0', role: 'listbox', 'aria-label': 'Инциденты',
				onkeydown: e => this.onListKey(e)});
			this.cardBody = el('div', {class: 'ia-card-body'});
			this.feedback = el('div', {class: 'ia-feedback-slot'});
			this.feedbackFor = null;
			this.card = el('section', {class: 'ia-card'}, this.cardBody, this.feedback);
			this.toastBox = el('div', {class: 'ia-toasts', 'aria-live': 'polite'});

			root.append(
				el('header', {class: 'ia-header'}, this.info,
					el('div', {class: 'ia-header-actions'}, this.liveBtn, this.refreshBtn)),
				this.banner,
				this.stats,
				el('div', {class: 'ia-layout'},
					el('aside', {class: 'ia-side'},
						el('div', {class: 'ia-toolbar'}, this.search, this.filters),
						this.list),
					this.card),
				this.toastBox);
			this.renderEmptyCard();
		}

		async start() {
			this.loadInfo();
			this.list.replaceChildren(...[0, 1, 2, 3].map(() => el('div', {class: 'ia-item ia-skeleton'})));
			await this.refresh(true);
			setInterval(() => {
				this.tickClock();
				if (!this.paused && !document.hidden) this.refresh(false);
			}, POLL_MS);
			document.addEventListener('visibilitychange', () => {
				if (!document.hidden && !this.paused) this.refresh(false);
			});
			window.addEventListener('hashchange', () => {
				const id = new URLSearchParams(location.hash.slice(1)).get('incident');
				if (id && id !== this.selected) this.select(id);
			});
		}

		// ---------- шапка ----------

		async loadInfo() {
			try {
				const info = await this.api.get('/api/info');
				this.info.replaceChildren(
					el('span', {class: 'ia-info-item'}, el('span', {class: 'ia-dot ia-dot-' + (info.zabbixVersion ? 'ok' : 'bad')}),
						info.zabbixVersion ? `Zabbix API ${info.zabbixVersion}` : `Zabbix недоступен: ${info.zabbixError || ''}`),
					el('span', {class: 'ia-info-item'}, el('span', {class: 'ia-dot ia-dot-' + (info.llmEnabled ? 'ok' : 'muted')}),
						info.llmEnabled ? `LLM: ${info.llmModel}` : 'LLM выключена — объяснение по шаблону'),
					el('span', {class: 'ia-info-item ia-muted'}, `разбор через ${info.analysisDelaySeconds} с после начала`));
			} catch (e) {
				this.info.replaceChildren(el('span', {class: 'ia-info-item'}, el('span', {class: 'ia-dot ia-dot-bad'}),
					'Сервис недоступен: ' + e.message));
			}
		}

		togglePause() {
			this.paused = !this.paused;
			this.liveBtn.classList.toggle('ia-paused', this.paused);
			this.tickClock();
			if (!this.paused) this.refresh(false);
		}

		tickClock() {
			this.root.querySelectorAll('.ia-ago[data-ts]').forEach(n => { n.textContent = fmtAgo(n.dataset.ts); });
			this.root.querySelectorAll('.ia-dur[data-from]').forEach(n => { n.textContent = fmtSpan(Date.now() - ms(n.dataset.from)); });
			this.liveText.textContent = this.paused ? 'пауза'
				: this.updatedAt ? 'обновлено ' + fmtAgo(new Date(this.updatedAt).toISOString()) : 'загрузка';
		}

		showBanner(text) {
			this.banner.hidden = !text;
			this.banner.textContent = text || '';
		}

		toast(text, tone) {
			const t = el('div', {class: `ia-toast ia-toast-${tone || 'ok'}`}, text);
			this.toastBox.append(t);
			setTimeout(() => t.classList.add('ia-toast-out'), 2600);
			setTimeout(() => t.remove(), 3000);
		}

		// ---------- данные ----------

		async refresh(force) {
			if (this.loading) return;
			this.loading = true;
			this.refreshBtn.disabled = true;
			try {
				const incidents = await this.api.get('/api/incidents?limit=200');
				this.showBanner(null);
				const key = JSON.stringify(incidents);
				if (force || key !== this.listKey) {
					this.listKey = key;
					this.incidents = incidents;
					this.renderStats();
					this.renderList(false);
				}
				this.updatedAt = Date.now();
				if (!this.selected && incidents.length) this.select(incidents[0].id, true);
				else if (this.selected) await this.loadCard(!force);
			} catch (e) {
				this.showBanner(`Нет связи с сервисом: ${e.message}. Данные на экране — последние полученные, повтор через 10 с.`);
				if (!this.incidents.length) this.list.replaceChildren(this.emptyState('⚠', 'Не удалось загрузить инциденты', e.message));
			} finally {
				this.loading = false;
				this.refreshBtn.disabled = false;
				this.tickClock();
			}
		}

		renderStats() {
			const all = this.incidents;
			const open = all.filter(i => i.status === 'OPEN').length;
			const done = all.filter(i => i.analysisStatus === 'DONE').length;
			const failed = all.filter(i => i.analysisStatus === 'FAILED').length;
			const problems = all.reduce((n, i) => n + (i.problemCount || 1), 0);
			const stat = (value, label, tone) => el('div', {class: 'ia-stat' + (tone ? ' ia-stat-' + tone : '')},
				el('div', {class: 'ia-stat-value'}, value), el('div', {class: 'ia-stat-label'}, label));
			this.stats.replaceChildren(
				stat(open, 'открыто', open ? 'bad' : null),
				stat(all.length, 'инцидентов'),
				stat(problems, 'проблем Zabbix в них'),
				stat(done, 'разобрано', 'ok'),
				failed ? stat(failed, 'с ошибкой разбора', 'bad') : null);
		}

		setFilter(key) {
			this.filter = key;
			this.filters.querySelectorAll('button').forEach(b => b.classList.toggle('ia-on', b.dataset.filter === key));
			this.renderList(true);
		}

		visible() {
			const q = this.query;
			return this.incidents.filter(i => this.filter === 'all' || i.status === this.filter).filter(i => !q
				|| [i.triggerName, i.topSuspect, ...(i.hosts || []).map(h => h.host)].some(s => s && s.toLowerCase().includes(q)));
		}

		renderList(resetScroll) {
			const scroll = this.list.scrollTop;
			const items = this.visible();
			if (!items.length) {
				this.list.replaceChildren(this.incidents.length
					? this.emptyState('⌕', 'Ничего не найдено', 'Измените фильтр или строку поиска.')
					: this.emptyState('✓', 'Инцидентов пока нет', 'Как только в Zabbix откроется проблема, здесь появится её разбор.'));
				return;
			}
			this.list.replaceChildren(...items.map(i => this.listItem(i)));
			this.list.scrollTop = resetScroll ? 0 : scroll;
		}

		listItem(i) {
			const [statusText, statusTone] = STATUS[i.status] || [i.status, 'muted'];
			const [analysisText, analysisTone] = ANALYSIS[i.analysisStatus] || [i.analysisStatus, 'muted'];
			return el('button', {type: 'button', role: 'option', class: 'ia-item ia-item-sev-' + i.severity
					+ (i.id === this.selected ? ' ia-selected' : '') + (i.status === 'RESOLVED' ? ' ia-item-resolved' : ''),
					'data-id': i.id, 'aria-selected': i.id === this.selected ? 'true' : 'false',
					onclick: () => this.select(i.id)},
				el('div', {class: 'ia-item-top'},
					sevPill(i.severity),
					el('span', {class: 'ia-item-time'}, ago(i.startedAt))),
				el('div', {class: 'ia-item-title'}, i.triggerName || 'Без названия'),
				el('div', {class: 'ia-item-meta'},
					pill(statusText, statusTone),
					pill(analysisText, analysisTone),
					(i.problemCount || 1) > 1 ? pill(`${i.problemCount} проблем`, 'accent', 'Связанные проблемы объединены в один инцидент') : null,
					i.topSuspect ? el('span', {class: 'ia-item-suspect', title: 'Вероятный источник'}, '→ ', el('b', {}, i.topSuspect)) : null));
		}

		onListKey(e) {
			if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
			const items = this.visible();
			if (!items.length) return;
			e.preventDefault();
			const at = items.findIndex(i => i.id === this.selected);
			const next = items[Math.max(0, Math.min(items.length - 1, at + (e.key === 'ArrowDown' ? 1 : -1)))];
			this.select(next.id);
			const node = this.list.querySelector(`[data-id="${next.id}"]`);
			if (node) node.scrollIntoView({block: 'nearest'});
		}

		select(id, quiet) {
			if (this.selected !== id) {
				this.selected = id;
				this.cardKey = null;
				history.replaceState(null, '', '#incident=' + encodeURIComponent(id));
			}
			this.list.querySelectorAll('.ia-item').forEach(n => {
				const on = n.dataset.id === id;
				n.classList.toggle('ia-selected', on);
				n.setAttribute('aria-selected', on ? 'true' : 'false');
			});
			if (!quiet || !this.detail) this.cardBody.replaceChildren(this.skeletonCard());
			return this.loadCard(false);
		}

		async loadCard(quiet) {
			const id = this.selected;
			let d;
			try {
				d = await this.api.get('/api/incidents/' + id);
			} catch (e) {
				if (!quiet && id === this.selected) {
					this.cardBody.replaceChildren(this.emptyState('⚠', 'Инцидент не загрузился', e.message));
				}
				return;
			}
			if (id !== this.selected) return;
			const key = JSON.stringify(d);
			if (key === this.cardKey) return;
			this.cardKey = key;
			this.detail = d;
			this.renderCard(d);
		}

		// ---------- карточка ----------

		renderEmptyCard() {
			this.cardBody.replaceChildren(this.emptyState('◎', 'Выберите инцидент', 'Слева — инциденты: связанные проблемы Zabbix, собранные в один разбор.'));
		}

		/** Перерисовать, сохранив раскрытые блоки. Форма оценки живёт отдельно и не трогается. */
		renderCard(d) {
			const open = new Set([...this.cardBody.querySelectorAll('details[data-key]')]
				.filter(n => n.open).map(n => n.dataset.key));
			const closed = new Set([...this.cardBody.querySelectorAll('details[data-key]')]
				.filter(n => !n.open).map(n => n.dataset.key));
			const sameIncident = this.cardBody.dataset.id === d.id;
			this.cardBody.dataset.id = d.id;
			this.cardBody.replaceChildren(...this.cardParts(d));
			if (sameIncident) {
				this.cardBody.querySelectorAll('details[data-key]').forEach(n => {
					if (open.has(n.dataset.key)) n.open = true;
					if (closed.has(n.dataset.key)) n.open = false;
				});
			}
			if (this.feedbackFor !== d.id) {
				this.feedbackFor = d.id;
				this.feedback.replaceChildren(this.canWrite ? this.feedbackForm(d.id) : '');
			}
		}

		cardParts(d) {
			const a = d.analysis;
			const [statusText, statusTone] = STATUS[d.status] || [d.status, 'muted'];
			const [analysisText, analysisTone] = ANALYSIS[d.analysisStatus] || [d.analysisStatus, 'muted'];
			const problems = d.problems && d.problems.length ? d.problems : [];

			const head = el('div', {class: 'ia-card-head ia-card-sev-' + d.severity},
				el('div', {class: 'ia-card-badges'}, sevPill(d.severity), pill(statusText, statusTone), pill(analysisText, analysisTone),
					problems.length > 1 ? pill(`${problems.length} проблем в инциденте`, 'accent') : null,
					this.canWrite ? el('button', {type: 'button', class: 'ia-btn ia-btn-ghost ia-btn-sm ia-card-tools',
						onclick: e => this.reanalyze(d.id, e.currentTarget)}, '↻ Разобрать заново') : null),
				el('h2', {class: 'ia-card-title'}, d.triggerName),
				el('div', {class: 'ia-card-meta'},
					el('span', {class: 'ia-meta-item'}, el('span', {class: 'ia-meta-label'}, 'Хосты'),
						el('span', {class: 'ia-chips'}, d.hosts.map(hostChip))),
					el('span', {class: 'ia-meta-item'}, el('span', {class: 'ia-meta-label'}, 'Начало'),
						fmtTime(d.startedAt), ' · ', ago(d.startedAt)),
					el('span', {class: 'ia-meta-item'}, el('span', {class: 'ia-meta-label'}, d.resolvedAt ? 'Длился' : 'Идёт'),
						duration(d.startedAt, d.resolvedAt)),
					el('span', {class: 'ia-meta-item'}, el('span', {class: 'ia-meta-label'}, 'Событие'), '#' + d.problemEventId)));

			const parts = [head];
			if (!a) {
				parts.push(this.analysisState(d));
			} else {
				parts.push(
					el('div', {class: 'ia-verdict'},
						el('div', {class: 'ia-verdict-label'}, 'Вывод'),
						el('div', {class: 'ia-verdict-text'}, a.explanation ? a.explanation.summary : 'Объяснения нет.'),
						el('div', {class: 'ia-verdict-meta'},
							a.explanation && a.explanation.generatedBy ? 'написал: ' + a.explanation.generatedBy + ' · ' : '',
							'разобран ', ago(a.analyzedAt),
							a.scope && a.scope.length ? ` · в разборе: ${a.scope.join(', ')}` : '')),
					d.analysisStatus === 'FAILED' && d.analysisError
						? el('div', {class: 'ia-alert ia-alert-bad'}, 'Повторный разбор не удался: ' + d.analysisError) : null,
					this.suspects(d, a),
					this.timeline(d, a));
			}
			parts.push(this.problemsSection(d));
			if (a) {
				parts.push(
					this.tableSection('deviations', 'Отклонения метрик', a.deviations.length,
						['Сервис', 'Метрика', 'Обычно', 'Стало', 'С'],
						a.deviations.map(m => [el('b', {}, m.host), m.metric, fmtNumber(m.expected),
							el('span', {class: 'ia-strong-bad'}, fmtNumber(m.actual) + (m.units ? ' ' + m.units : '')),
							this.offsetCell(d, m.startedAt)]), true),
					this.tableSection('logs', 'Строки логов', a.logEvidence.length, ['Сервис', 'Строка', 'Раз', ''],
						a.logEvidence.map(l => [el('b', {}, l.host), el('code', {class: 'ia-code'}, l.example), l.count,
							l.newInWindow ? pill('новая', 'warn') : '']), a.logEvidence.length > 0),
					a.explanation && a.explanation.details
						? el('details', {class: 'ia-details', 'data-key': 'explain'},
							el('summary', {}, 'Подробное объяснение'),
							el('pre', {class: 'ia-pre'}, a.explanation.details))
						: null);
			}
			if (d.feedback && d.feedback.length) {
				parts.push(el('details', {class: 'ia-details', 'data-key': 'feedback-history'},
					el('summary', {}, `Оценки инженеров (${d.feedback.length})`),
					el('ul', {class: 'ia-feedback-list'}, d.feedback.map(f => el('li', {},
						pill(f.verdict === 'CORRECT' ? 'верно' : 'неверно', f.verdict === 'CORRECT' ? 'ok' : 'bad'),
						' ', el('b', {}, f.author || 'аноним'), ' · ', ago(f.createdAt),
						f.actualSource ? el('div', {}, 'настоящий источник: ', el('b', {}, f.actualSource)) : null,
						f.comment ? el('div', {class: 'ia-muted'}, f.comment) : null)))));
			}
			return parts;
		}

		analysisState(d) {
			if (d.analysisStatus === 'FAILED') {
				return el('div', {class: 'ia-alert ia-alert-bad'}, el('b', {}, 'Разбор не удался. '), d.analysisError || '',
					el('div', {class: 'ia-muted'}, 'Нажмите «Разобрать заново», когда причина устранена.'));
			}
			const text = d.analysisStatus === 'RUNNING' ? 'Идёт разбор: собираю метрики и логи соседей…'
				: 'Разбор в очереди: ждём, пока наберутся данные после начала проблемы.';
			return el('div', {class: 'ia-alert ia-alert-info'}, el('span', {class: 'ia-spinner'}), text);
		}

		suspects(d, a) {
			if (!a.suspects.length) return null;
			const max = Math.max(...a.suspects.map(s => s.score), 1e-9);
			return el('div', {class: 'ia-section'},
				el('h3', {class: 'ia-h'}, 'Подозреваемые', el('span', {class: 'ia-h-note'}, 'по убыванию вероятности')),
				el('ol', {class: 'ia-suspects'}, a.suspects.map((s, n) => el('li', {class: 'ia-suspect' + (n === 0 ? ' ia-suspect-top' : '')},
					el('span', {class: 'ia-rank'}, n + 1),
					el('div', {class: 'ia-suspect-main'},
						el('div', {class: 'ia-suspect-row'},
							el('span', {class: 'ia-suspect-host'}, s.host),
							s.firstDeviationAt ? el('span', {class: 'ia-muted'}, 'отклонился ', this.offsetCell(d, s.firstDeviationAt)) : null,
							el('span', {class: 'ia-suspect-score'}, fmtNumber(s.score))),
						el('div', {class: 'ia-bar'}, el('span', {style: `width:${Math.max(4, s.score / max * 100)}%`})),
						s.reasons && s.reasons.length ? el('div', {class: 'ia-suspect-reasons'}, s.reasons.join(' · ')) : null)))));
		}

		offsetCell(d, iso) {
			if (!iso) return '—';
			return el('span', {class: 'ia-offset', title: fmtTime(iso)}, fmtOffset(ms(iso) - ms(d.startedAt)));
		}

		/** Линия времени по сервисам: ● — первое отклонение метрики, ▲ — начало проблемы Zabbix. */
		timeline(d, a) {
			const problems = d.problems || [];
			const hosts = [];
			const add = h => { if (h && !hosts.includes(h)) hosts.push(h); };
			a.suspects.forEach(s => add(s.host));
			problems.forEach(p => p.hosts.forEach(h => add(h.host)));
			const devs = new Map(a.suspects.filter(s => s.firstDeviationAt).map(s => [s.host, ms(s.firstDeviationAt)]));
			const starts = problems.flatMap(p => p.hosts.map(h => ({host: h.host, t: ms(p.startedAt), name: p.triggerName})));
			const times = [...devs.values(), ...starts.map(p => p.t), ms(d.startedAt)];
			if (hosts.length === 0 || times.length < 2) return null;
			let min = Math.min(...times), max = Math.max(...times);
			const pad = Math.max((max - min) * 0.06, 5000);
			min -= pad; max += pad;
			const width = 720, rowH = 30, left = 110, right = 16, top = 8, plot = width - left - right;
			const height = top + hosts.length * rowH + 34;
			const x = t => left + (t - min) / (max - min) * plot;
			const root = svg('svg', {viewBox: `0 0 ${width} ${height}`, class: 'ia-timeline', role: 'img',
				'aria-label': 'Линия времени отклонений и проблем'});
			const axisY = top + hosts.length * rowH + 6;
			hosts.forEach((h, i) => {
				const y = top + i * rowH + rowH / 2;
				if (i % 2 === 0) root.append(svg('rect', {x: 0, y: y - rowH / 2, width, height: rowH, class: 'ia-tl-band'}));
				root.append(svg('text', {x: 8, y: y + 4, class: 'ia-tl-host'}, h));
				root.append(svg('line', {x1: left, x2: width - right, y1: y, y2: y, class: 'ia-tl-row'}));
				if (devs.has(h)) {
					const cx = x(devs.get(h));
					root.append(svg('line', {x1: cx, x2: width - right, y1: y, y2: y, class: 'ia-tl-span'}));
					const dot = svg('circle', {cx, cy: y, r: 6, class: 'ia-tl-dot'});
					dot.append(svg('title', {}, `${h}: отклонение ${fmtOffset(devs.get(h) - ms(d.startedAt))}`));
					root.append(dot);
				}
				starts.filter(p => p.host === h).forEach(p => {
					const px = x(p.t);
					const tri = svg('path', {d: `M${px} ${y - 7} L${px + 6} ${y + 5} L${px - 6} ${y + 5} Z`, class: 'ia-tl-problem'});
					tri.append(svg('title', {}, `${p.name}: ${fmtOffset(p.t - ms(d.startedAt))}`));
					root.append(tri);
				});
			});
			const sx = x(ms(d.startedAt));
			root.append(svg('line', {x1: sx, x2: sx, y1: top, y2: axisY, class: 'ia-tl-start'}));
			root.append(svg('line', {x1: left, x2: width - right, y1: axisY, y2: axisY, class: 'ia-tl-axis'}));
			const ticks = 5;
			for (let k = 0; k <= ticks; k++) {
				const t = min + (max - min) * k / ticks;
				root.append(svg('text', {x: x(t), y: axisY + 18, class: 'ia-tl-tick', 'text-anchor': k === 0 ? 'start' : k === ticks ? 'end' : 'middle'},
					fmtOffset(t - ms(d.startedAt))));
			}
			return el('div', {class: 'ia-section'},
				el('h3', {class: 'ia-h'}, 'Как развивался сбой'),
				el('div', {class: 'ia-legend'},
					el('span', {}, el('i', {class: 'ia-lg-dot'}), 'отклонение метрики'),
					el('span', {}, el('i', {class: 'ia-lg-tri'}), 'проблема в Zabbix'),
					el('span', {}, el('i', {class: 'ia-lg-start'}), 'начало инцидента')),
				el('div', {class: 'ia-timeline-wrap'}, root));
		}

		problemsSection(d) {
			const problems = d.problems || [];
			if (!problems.length) return null;
			return this.tableSection('problems', 'Проблемы Zabbix в инциденте', problems.length,
				['', 'Триггер', 'Хост', 'Начало', 'Состояние'],
				problems.map(p => [
					el('span', {class: `ia-sev-dot ia-sev-bg-${p.severity}`, title: SEVERITY[p.severity]}),
					p.triggerName,
					el('span', {class: 'ia-chips'}, p.hosts.map(hostChip)),
					this.offsetCell(d, p.startedAt),
					p.resolvedAt ? pill('закрыта через ' + fmtSpan(ms(p.resolvedAt) - ms(p.startedAt)), 'ok') : pill('открыта', 'bad')]),
				true);
		}

		tableSection(key, title, count, head, rows, openByDefault) {
			if (!rows.length) return null;
			return el('details', {class: 'ia-details ia-table-section', 'data-key': key, open: openByDefault},
				el('summary', {}, title, el('span', {class: 'ia-count'}, count)),
				el('div', {class: 'ia-table-wrap'},
					el('table', {class: 'ia-table'},
						el('thead', {}, el('tr', {}, head.map(h => el('th', {}, h)))),
						el('tbody', {}, rows.map(r => el('tr', {}, r.map(c => el('td', {}, c))))))));
		}

		async reanalyze(id, button) {
			button.disabled = true;
			try {
				await this.api.post(`/api/incidents/${id}/reanalyze`);
				this.toast('Разбор запущен заново');
				setTimeout(() => this.refresh(false), 1500);
			} catch (e) {
				this.toast('Не удалось: ' + e.message, 'bad');
			} finally {
				button.disabled = false;
			}
		}

		/** Форма оценки разбора. Создаётся один раз на инцидент — автообновление её не трогает. */
		feedbackForm(id) {
			let verdict = null;
			const status = el('span', {class: 'ia-muted'});
			const source = el('input', {type: 'text', class: 'ia-field', name: 'actualSource', placeholder: 'например, database',
				autocomplete: 'off'});
			const comment = el('textarea', {class: 'ia-field ia-textarea', name: 'comment', rows: 2,
				placeholder: 'Что на самом деле случилось — необязательно'});
			const submit = el('button', {type: 'submit', class: 'ia-btn ia-btn-primary', disabled: true}, 'Отправить оценку');
			const choice = el('div', {class: 'ia-segmented ia-verdict-choice'},
				[['CORRECT', '👍 Разбор верный'], ['WRONG', '👎 Неверный']].map(([v, label]) =>
					el('button', {type: 'button', 'data-verdict': v, onclick: () => {
						verdict = v;
						choice.querySelectorAll('button').forEach(b => b.classList.toggle('ia-on', b.dataset.verdict === v));
						sourceRow.hidden = v !== 'WRONG';
						submit.disabled = false;
					}}, label)));
			const sourceRow = el('label', {class: 'ia-form-row', hidden: true},
				el('span', {class: 'ia-form-label'}, 'Настоящий источник'), source);
			const form = el('form', {class: 'ia-feedback', onsubmit: async e => {
				e.preventDefault();
				if (!verdict) return;
				submit.disabled = true;
				status.textContent = 'Отправляю…';
				try {
					await this.api.post(`/api/incidents/${id}/feedback`,
						{verdict, actualSource: source.value.trim() || null, comment: comment.value.trim() || null});
					source.value = '';
					comment.value = '';
					verdict = null;
					choice.querySelectorAll('button').forEach(b => b.classList.remove('ia-on'));
					sourceRow.hidden = true;
					status.textContent = '';
					this.toast('Спасибо, оценка учтена');
					this.cardKey = null;
					this.loadCard(true);
				} catch (err) {
					status.textContent = '';
					submit.disabled = false;
					this.toast('Оценка не отправлена: ' + err.message, 'bad');
				}
			}},
				el('div', {class: 'ia-feedback-head'},
					el('h3', {class: 'ia-h'}, 'Оценка разбора'),
					el('span', {class: 'ia-h-note'}, 'помогает калибровать точность')),
				choice, sourceRow,
				el('label', {class: 'ia-form-row'}, el('span', {class: 'ia-form-label'}, 'Комментарий'), comment),
				el('div', {class: 'ia-form-actions'}, submit, status));
			return form;
		}

		// ---------- заглушки ----------

		emptyState(icon, title, text) {
			return el('div', {class: 'ia-empty'}, el('div', {class: 'ia-empty-icon'}, icon),
				el('div', {class: 'ia-empty-title'}, title), text ? el('div', {class: 'ia-muted'}, text) : null);
		}

		skeletonCard() {
			return el('div', {class: 'ia-skeleton-card'},
				el('div', {class: 'ia-skeleton ia-sk-line ia-sk-w30'}),
				el('div', {class: 'ia-skeleton ia-sk-title'}),
				el('div', {class: 'ia-skeleton ia-sk-line ia-sk-w60'}),
				el('div', {class: 'ia-skeleton ia-sk-block'}),
				el('div', {class: 'ia-skeleton ia-sk-block'}));
		}
	}

	function boot() {
		const root = document.getElementById('incident-advisor');
		if (root) new App(root).start();
	}

	if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
	else boot();
})();
