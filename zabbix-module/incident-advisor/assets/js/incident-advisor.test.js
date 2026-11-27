'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// Достаточная для панели DOM-модель без сетевых запросов и внешних зависимостей.
class Node {
	constructor(tag, text = '') {
		this.tag = tag;
		this.children = [];
		this.attributes = {};
		this.listeners = {};
		this.value = '';
		this.open = false;
		this._text = text;
	}
	setAttribute(key, value) { this.attributes[key] = String(value); }
	addEventListener(key, listener) { this.listeners[key] = listener; }
	append(...nodes) { this.children.push(...nodes); }
	replaceChildren(...nodes) { this.children = nodes; this._text = ''; }
	set textContent(value) { this._text = String(value); this.children = []; }
	get textContent() { return this._text + this.children.map(node => node.textContent).join(''); }
}
const document = {
	createElement: tag => new Node(tag),
	createTextNode: text => new Node('#text', text),
	readyState: 'loading',
	addEventListener() {}
};
const source = fs.readFileSync(path.join(__dirname, 'incident-advisor.js'), 'utf8')
	.replace('\tfunction boot() {', '\tglobalThis.__test = {App, EvaluationPanel, evaluationRatio, evaluationMetrics, evaluationByType};\n\tfunction boot() {');
const context = {document, Node, URLSearchParams, console};
vm.runInNewContext(source, context);
const {App, EvaluationPanel, evaluationRatio, evaluationMetrics, evaluationByType} = context.__test;
const flush = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => {
	let resolve, reject;
	const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
	return {promise, resolve, reject};
};
const report = overrides => ({faults: 4, matched: 2, sourceCorrect: 1, sourceInTop3: 2,
	meanTimeToAnswer: null, timeToAnswerObservations: 0, baselineObservations: 1,
	baselineCorrectCount: 0, pairedAdvisorCorrectCount: 1, outsideWindowIncidentIds: null,
	rows: [], ...overrides});
const run = (id, overrides = {}) => ({id, createdAt: '2026-01-01T00:00:00Z', from: '2026-01-01T00:00:00Z',
	to: '2026-01-02T00:00:00Z', schemaVersion: 1, methodologyVersion: 'v1', scope: 'window_approximation',
	labels: [], incidents: [], report: report(), ...overrides});

test('метрики показывают честные дроби, нулевые знаменатели и парный baseline', () => {
	assert.equal(evaluationRatio(0, 0), '0 / 0 (недоступно)');
	assert.equal(evaluationRatio(null, 4), '— / 4 (недоступно)');
	assert.equal(evaluationRatio(1, 4), '1 / 4 (25%)');
	const metrics = Object.fromEntries(evaluationMetrics(report()));
	assert.equal(metrics['Источник на 1-м месте / все сбои'], '1 / 4 (25%)');
	assert.equal(metrics['Источник на 1-м месте / сопоставленные'], '1 / 2 (50%)');
	assert.equal(metrics['Baseline, верно / парные наблюдения'], '0 / 1 (0%)');
	assert.equal(metrics['Advisor, верно / те же пары'], '1 / 1 (100%)');
	assert.equal(metrics['Инциденты вне окон сбоев'], '—');
});

test('сводка по типам учитывает все сбои и парный baseline только для сопоставленных', () => {
	const rows = [
		{type: 'LATENCY', matchStatus: 'MATCHED', rankOfExpected: 1, baselineCorrect: false},
		{type: 'LATENCY', matchStatus: 'MATCHED', rankOfExpected: 3, baselineCorrect: true},
		{type: 'LATENCY', matchStatus: 'AMBIGUOUS', rankOfExpected: 1, baselineCorrect: true},
		{type: 'ERRORS', matchStatus: 'NO_INCIDENT'},
		{type: null, matchStatus: 'NO_ANALYSIS'}
	];
	const groups = Array.from(evaluationByType(rows), row => Array.from(row));
	assert.deepEqual(groups, [
		['LATENCY', '3', '2 / 3 (66.67%)', '1 / 3 (33.33%)', '2 / 3 (66.67%)', '1 / 2 (50%)', '1 / 2 (50%)'],
		['ERRORS', '1', '0 / 1 (0%)', '0 / 1 (0%)', '0 / 1 (0%)', '0 / 0 (недоступно)', '0 / 0 (недоступно)'],
		['—', '1', '0 / 1 (0%)', '0 / 1 (0%)', '0 / 1 (0%)', '0 / 0 (недоступно)', '0 / 0 (недоступно)']
	]);
	const panel = new EvaluationPanel({});
	assert.match(panel.runDetails('A', run('a', {report: report({rows})})).textContent, /По типам сбоев/);
});

test('ленивая загрузка, пагинация и устаревшие ответы списка', async () => {
	const calls = [];
	const api = {get(url) { const d = deferred(); calls.push({url, ...d}); return d.promise; }};
	const panel = new EvaluationPanel(api);
	assert.equal(calls.length, 0);
	panel.panel.open = true;
	panel.panel.listeners.toggle();
	assert.equal(calls[0].url, '/api/evaluation/runs?limit=50&offset=0');
	calls[0].resolve(Array.from({length: 50}, (_, i) => ({id: String(i)})));
	await flush();
	assert.equal(panel.next.disabled, false);
	panel.next.listeners.click();
	assert.equal(calls[1].url, '/api/evaluation/runs?limit=50&offset=50');
	panel.refresh.listeners.click();
	calls[2].resolve([{id: 'new'}]);
	await flush();
	calls[1].resolve([{id: 'stale'}]);
	await flush();
	assert.equal(panel.summaries[0].id, 'new');
	assert.equal(panel.offset, 0);
});

test('выбор A/B защищён от старых ответов, показывает различия и безопасный текст', async () => {
	const calls = [];
	const panel = new EvaluationPanel({get(url) { const d = deferred(); calls.push({url, ...d}); return d.promise; }});
	const first = panel.select('A', 'old');
	const second = panel.select('A', 'new');
	calls[1].resolve(run('new', {report: report({rows: [{faultId: '<img onerror=alert(1)>', matchStatus: 'NO_INCIDENT'}]})}));
	await second;
	calls[0].resolve(run('old'));
	await first;
	assert.equal(panel.runs.A.id, 'new');
	assert.match(panel.results.textContent, /<img onerror=alert\(1\)>/);
	assert.equal(panel.results.textContent.includes('old'), false);
	const compare = panel.select('B', 'other');
	calls[2].resolve(run('new'));
	calls[3].resolve(run('other', {methodologyVersion: 'v2', labels: [{faultId: 'x'}]}));
	await compare;
	assert.match(panel.results.textContent, /Версии, область или окна снимков различаются/);
	assert.match(panel.results.textContent, /Состав сохранённых входов различается/);
	assert.match(panel.results.textContent, /Сравнение A \/ B/);
});

test('панель точности видна только пользователям с правами администратора', () => {
	const makeApp = canManageRetention => {
		const root = new Node('div');
		root.dataset = {csrf: '', canWrite: '1', canManageRetention};
		context.location = {hash: ''};
		document.createDocumentFragment = () => new Node('#fragment');
		new App(root);
		return root.textContent;
	};
	assert.match(makeApp('1'), /Точность/);
	assert.doesNotMatch(makeApp('0'), /Точность/);
});

test('ошибки не раскрывают ответ сервера, выбор сохраняется при пагинации', async () => {
	const panel = new EvaluationPanel({get: async () => { throw new Error('SECRET_TOKEN'); }});
	await panel.loadPage(0);
	assert.match(panel.status.textContent, /Не удалось/);
	assert.doesNotMatch(panel.status.textContent, /SECRET_TOKEN/);
	await panel.select('A', 'chosen');
	assert.match(panel.results.textContent, /не загрузился/);
	assert.doesNotMatch(panel.results.textContent, /SECRET_TOKEN/);
	assert.equal(panel.selects.A.value, 'chosen');
	panel.summaries = [{id: 'other'}];
	panel.renderControls();
	assert.equal(panel.selects.A.value, 'chosen');
});
