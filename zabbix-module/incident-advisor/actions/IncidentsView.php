<?php declare(strict_types = 0);

namespace Modules\IncidentAdvisor\Actions;

use CController;
use CControllerResponseData;
use CCsrfTokenHelper;

/** Страница модуля. Вся отрисовка — в assets/js/incident-advisor.js. */
class IncidentsView extends CController {

	protected function init(): void {
		$this->disableCsrfValidation();
	}

	protected function checkInput(): bool {
		return true;
	}

	protected function checkPermissions(): bool {
		return $this->getUserType() >= USER_TYPE_ZABBIX_USER;
	}

	protected function doAction(): void {
		$response = new CControllerResponseData([
			'csrf_token' => CCsrfTokenHelper::get('incidentadvisor.api.post'),
			'can_write' => $this->getUserType() >= USER_TYPE_ZABBIX_USER
		]);
		$response->setTitle(_('Incident advisor'));
		$this->setResponse($response);
	}
}
