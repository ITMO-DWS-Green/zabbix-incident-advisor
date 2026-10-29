<?php declare(strict_types = 0);

namespace Modules\IncidentAdvisor\Actions;

use CController;
use CControllerResponseData;

/** Чтение: zabbix.php?action=incidentadvisor.api.get&path=/api/incidents */
class ApiGet extends CController {

	private int $status = 200;

	protected function init(): void {
		$this->disableCsrfValidation();
	}

	protected function checkInput(): bool {
		return $this->validateInput(['path' => 'required|string']);
	}

	protected function checkPermissions(): bool {
		return $this->getUserType() >= USER_TYPE_ZABBIX_USER;
	}

	protected function doAction(): void {
		[$this->status, $body] = ServiceClient::forward('GET', $this->getInput('path'));
		http_response_code($this->status);
		$this->setResponse(new CControllerResponseData(['main_block' => $body]));
	}
}
