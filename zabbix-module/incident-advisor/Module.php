<?php declare(strict_types = 0);

namespace Modules\IncidentAdvisor;

use APP;
use CMenuItem;
use Zabbix\Core\CModule;

/**
 * Добавляет пункт «Monitoring → Incident advisor». Больше PHP-логики в модуле нет:
 * интерфейс — на JS (assets/js), данные — из Java-сервиса через прокси-действия.
 */
class Module extends CModule {

	public function init(): void {
		APP::Component()->get('menu.main')
			->findOrAdd(_('Monitoring'))
			->getSubmenu()
			->insertAfter(_('Problems'),
				(new CMenuItem(_('Incident advisor')))->setAction('incidentadvisor.view')
			);
	}
}
