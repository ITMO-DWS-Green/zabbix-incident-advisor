/*
 * Интерфейс модуля Incident advisor. Без сборки и зависимостей — обычный JS.
 * Данные идут через прокси-действия модуля в Java-сервис:
 *   GET  zabbix.php?action=incidentadvisor.api.get&path=/api/...
 *   POST zabbix.php?action=incidentadvisor.api.post  (path, body, _csrf_token)
 */
(function () {
	'use strict';

	const SEVERITY = ['Not classified', 'Information', 'Warning', 'Average', 'High', 'Disaster'];
	const POLL_MS = 10000;

	function el(tag, attrs, ...children) {
		const node = document.createElement(tag);
		Object.entries(attrs || {}).forEach(([k, v]) => {
			if (k === 'class') node.className = v;
			else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
			else if (v !== null && v !== undefined) node.setAttribute(k, v);
		});
		children.flat().forEach(c => node.append(c instanceof Node ? c : document.createTextNode(c ?? '')));
		return node;
	}

	function fmtTime(iso) {
		return iso ? new Date(iso).toLocaleString() : '—';
	}

	function fmtNumber(v) {
		if (v === null || v === undefined) return '—';
		return Math.abs(v) >= 100 ? Math.round(v).toString() : Number(v).toFixed(3);
	}

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
			const data = text ? JSON.parse(text) : null;
			if (!res.ok) throw new Error((data && data.error) || res.statusText);
			return data;
		}
	}

	class App {
		constructor(root) {
			this.root = root;
			this.api = new Api(root.dataset.csrf);
			this.canWrite = root.dataset.canWrite === '1';
			this.selected = null;
			this.list = el('div', {class: 'ia-list'});
			this.card = el('div', {class: 'ia-card'}, el('p', {class: 'ia-muted'}, 'Выберите инцидент слева.'));
			this.status = el('div', {class: 'ia-status'});
			root.append(this.status, el('div', {class: 'ia-layout'}, this.list, this.card));
		}

		async start() {
			this.loadInfo();
			await this.loadList();
			setInterval(() => this.loadList(), POLL_MS);
		}

		async loadInfo() {
			try {
				const info = await this.api.get('/api/info');
				const llm = info.llmEnabled ? `LLM: ${info.llmModel}` : 'LLM выключена (шаблон)';
				const zbx = info.zabbixVersion ? `Zabbix API ${info.zabbixVersion}` : `Zabbix: ${info.zabbixError}`;
				this.status.textContent = `${zbx} · ${llm} · разбор через ${info.analysisDelaySeconds} с после начала проблемы`;
			} catch (e) {
				this.status.textContent = 'Сервис недоступен: ' + e.message;
				this.status.classList.add('ia-error');
			}
		}

		async loadList() {
			let incidents;
			try {
				incidents = await this.api.get('/api/incidents?limit=100');
			} catch (e) {
				this.list.replaceChildren(el('p', {class: 'ia-error'}, 'Не удалось загрузить: ' + e.message));
				return;
			}
			if (!incidents.length) {
				this.list.replaceChildren(el('p', {class: 'ia-muted'}, 'Инцидентов пока нет.'));
				return;
			}
			this.list.replaceChildren(...incidents.map(i => el('button', {
				class: 'ia-item' + (i.id === this.selected ? ' ia-selected' : ''),
				onclick: () => this.open(i.id)
			},
				el('div', {class: 'ia-item-title'},
					el('span', {class: `ia-sev ia-sev-${i.severity}`}, SEVERITY[i.severity] || i.severity),
					i.triggerName),
				el('div', {class: 'ia-muted'}, `${fmtTime(i.startedAt)} · ${i.status} · разбор: ${i.analysisStatus}`),
				i.topSuspect ? el('div', {}, 'Источник: ', el('b', {}, i.topSuspect)) : ''
			)));
			if (this.selected) this.open(this.selected, true);
		}

		async open(id, quiet) {
			this.selected = id;
			if (!quiet) this.loadList();
			let d;
			try {
				d = await this.api.get('/api/incidents/' + id);
			} catch (e) {
				if (!quiet) this.card.replaceChildren(el('p', {class: 'ia-error'}, e.message));
				return;
			}
			const a = d.analysis;
			const parts = [
				el('h3', {}, d.triggerName),
				el('div', {class: 'ia-muted'},
					`${SEVERITY[d.severity] || d.severity} · ${d.hosts.map(h => h.host).join(', ')} · ` +
					`начало ${fmtTime(d.startedAt)}` + (d.resolvedAt ? ` · закрыта ${fmtTime(d.resolvedAt)}` : '')),
			];
			if (!a) {
				parts.push(el('p', {}, d.analysisStatus === 'FAILED'
					? 'Разбор не удался: ' + d.analysisError
					: 'Разбор ещё не готов (' + d.analysisStatus + ').'));
			} else {
				parts.push(
					el('p', {class: 'ia-summary'}, a.explanation ? a.explanation.summary : ''),
					this.timeline(d, a),
					this.section('Подозреваемые', ['Сервис', 'Оценка', 'Отклонился с', 'Почему'],
						a.suspects.map(s => [s.host, fmtNumber(s.score), fmtTime(s.firstDeviationAt), s.reasons.join('; ')])),
					this.section('Отклонения метрик', ['Сервис', 'Метрика', 'Обычно', 'Стало', 'С'],
						a.deviations.map(m => [m.host, m.metric, fmtNumber(m.expected), fmtNumber(m.actual) + ' ' + (m.units || ''), fmtTime(m.startedAt)])),
					this.section('Логи', ['Сервис', 'Строка', 'Раз', 'Новая'],
						a.logEvidence.map(l => [l.host, l.example, l.count, l.newInWindow ? 'да' : ''])),
					a.explanation && a.explanation.details
						? el('details', {}, el('summary', {}, 'Подробно (' + a.explanation.generatedBy + ')'),
							el('pre', {class: 'ia-pre'}, a.explanation.details))
						: ''
				);
			}
			parts.push(this.actions(d));
			this.card.replaceChildren(...parts);
		}

		section(title, head, rows) {
			if (!rows.length) return el('div', {});
			return el('div', {class: 'ia-section'},
				el('h4', {}, title),
				el('table', {class: 'list-table'},
					el('thead', {}, el('tr', {}, head.map(h => el('th', {}, h)))),
					el('tbody', {}, rows.map(r => el('tr', {}, r.map(c => el('td', {}, String(c))))))));
		}

		/** Линия времени: когда отклонился каждый сервис относительно начала проблемы. */
		timeline(d, a) {
			const marks = a.suspects.filter(s => s.firstDeviationAt);
			if (!marks.length) return el('div', {});
			const start = new Date(d.startedAt).getTime();
			const times = marks.map(s => new Date(s.firstDeviationAt).getTime()).concat(start);
			const min = Math.min(...times), max = Math.max(...times), span = Math.max(max - min, 1);
			const width = 640, rowH = 26, left = 120, plot = width - left - 20;
			const x = t => left + (t - min) / span * plot;
			const svgNs = 'http://www.w3.org/2000/svg';
			const svg = document.createElementNS(svgNs, 'svg');
			svg.setAttribute('viewBox', `0 0 ${width} ${marks.length * rowH + 30}`);
			svg.setAttribute('class', 'ia-timeline');
			const add = (tag, attrs, text) => {
				const n = document.createElementNS(svgNs, tag);
				Object.entries(attrs).forEach(([k, v]) => n.setAttribute(k, v));
				if (text) n.textContent = text;
				svg.append(n);
			};
			add('line', {x1: x(start), x2: x(start), y1: 0, y2: marks.length * rowH + 4, class: 'ia-tl-start'});
			add('text', {x: x(start) + 4, y: marks.length * rowH + 20, class: 'ia-tl-label'}, 'начало проблемы');
			marks.forEach((s, i) => {
				const y = i * rowH + rowH / 2;
				add('text', {x: 0, y: y + 4, class: 'ia-tl-label'}, s.host);
				add('line', {x1: left, x2: width - 20, y1: y, y2: y, class: 'ia-tl-row'});
				add('circle', {cx: x(new Date(s.firstDeviationAt).getTime()), cy: y, r: 5, class: 'ia-tl-dot'});
			});
			return el('div', {class: 'ia-section'}, el('h4', {}, 'Кто отклонился первым'), svg);
		}

		actions(d) {
			const box = el('div', {class: 'ia-actions'});
			if (!this.canWrite) return box;
			const source = el('input', {type: 'text', placeholder: 'настоящий источник', class: 'ia-input'});
			const comment = el('input', {type: 'text', placeholder: 'комментарий', class: 'ia-input'});
			const send = verdict => this.api.post(`/api/incidents/${d.id}/feedback`,
				{verdict, actualSource: source.value || null, comment: comment.value || null})
				.then(() => { box.append(el('span', {class: 'ia-muted'}, ' Спасибо, учтено.')); })
				.catch(e => alert(e.message));
			box.append(
				el('button', {class: 'btn-alt', onclick: () => send('CORRECT')}, 'Разбор верный'),
				el('button', {class: 'btn-alt', onclick: () => send('WRONG')}, 'Неверный'),
				source, comment,
				el('button', {class: 'btn-alt', onclick: () => this.api.post(`/api/incidents/${d.id}/reanalyze`)
					.then(() => this.open(d.id)).catch(e => alert(e.message))}, 'Разобрать заново'));
			return box;
		}
	}

	function boot() {
		const root = document.getElementById('incident-advisor');
		if (root) new App(root).start();
	}

	if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
	else boot();
})();
