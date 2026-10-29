<?php declare(strict_types = 0);

namespace Modules\IncidentAdvisor\Actions;

use CController;
use CControllerResponseData;

/**
 * Запись: POST zabbix.php?action=incidentadvisor.api.post, поля path, body, _csrf_token.
 * CSRF проверяет сам Zabbix — токен страница получает в IncidentsView.
 */
class ApiPost extends CController {

	protected function checkInput(): bool {
		return $this->validateInput(['path' => 'required|string', 'body' => 'string']);
	}

	protected function checkPermissions(): bool {
		return $this->getUserType() >= USER_TYPE_ZABBIX_USER;
	}

	protected function doAction(): void {
		[$status, $body] = ServiceClient::forward('POST', $this->getInput('path'), $this->getInput('body', '{}'));
		http_response_code($status);
		$this->setResponse(new CControllerResponseData(['main_block' => $body]));
	}
}
