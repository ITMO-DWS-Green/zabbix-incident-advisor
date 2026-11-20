var Telegram = {
	params: {},

	setParams: function (params) {
		var required = ['api_token', 'api_chat_id', 'alert_message'];
		required.forEach(function (field) {
			if (typeof params !== 'object' || typeof params[field] !== 'string' || params[field].trim() === '') {
				throw 'Missing required parameter: "' + field + '".';
			}
		});

		var match = params.api_chat_id.match(/^(-?\d+|@[a-zA-Z0-9_]+)(?::(\d+))?$/);
		if (!match) {
			throw 'Invalid api_chat_id "' + params.api_chat_id +
				'": expected a numeric chat id or @channel, optionally ":topic".';
		}

		this.params = params;
		this.chatId = match[1];
		this.threadId = match[2];
		// По умолчанию — боевой Telegram; на стенде setup подставляет адрес заглушки.
		this.apiUrl = (params.api_url && params.api_url.trim() !== '')
			? params.api_url.replace(/\/+$/, '')
			: 'https://api.telegram.org/bot';
	},

	request: function () {
		var data = {
			chat_id: this.chatId,
			text: this.params.alert_message,
			disable_web_page_preview: true
		};
		if (this.threadId) {
			data.message_thread_id = this.threadId;
		}
		if (['markdown', 'html', 'markdownv2'].indexOf((this.params.api_parse_mode || '').toLowerCase()) !== -1) {
			data.parse_mode = this.params.api_parse_mode.toLowerCase();
		}

		var request = new HttpRequest();
		request.addHeader('Content-Type: application/json');
		var raw = request.post(this.apiUrl + this.params.api_token + '/sendMessage', JSON.stringify(data));

		if (request.getStatus() !== 200) {
			throw 'Telegram API returned HTTP ' + request.getStatus() + ': ' + raw;
		}
		var response;
		try {
			response = JSON.parse(raw);
		} catch (error) {
			throw 'Telegram API returned non-JSON: ' + raw;
		}
		if (!response.ok) {
			throw response.description || 'Telegram API rejected the message.';
		}
		return response;
	}
};

try {
	var params = JSON.parse(value);
	Telegram.setParams(params);
	Telegram.request();
	return 'OK';
} catch (error) {
	Zabbix.log(3, '[incident-advisor Telegram] ' + error);
	throw 'Sending failed: ' + error;
}
