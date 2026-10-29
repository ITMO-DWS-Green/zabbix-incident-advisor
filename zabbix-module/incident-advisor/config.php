<?php declare(strict_types = 0);

/**
 * Где Java-сервис и с каким токеном к нему ходить. Значения — из переменных окружения
 * контейнера zabbix-web (см. compose.yaml), иначе значения для разработки.
 */
return [
	'service_url' => getenv('INCIDENT_ADVISOR_URL') ?: 'http://advisor:8080',
	'service_token' => getenv('INCIDENT_ADVISOR_TOKEN') ?: 'dev-ui-token',
	'timeout' => 15
];
