<?php declare(strict_types = 0);

namespace Modules\IncidentAdvisor\Actions;

use CWebUser;

/**
 * Пересылает запрос интерфейса в Java-сервис. Пропускает только пути /api/..., добавляет общий токен
 * и имя пользователя Zabbix — сервис доверяет этому прокси, а не браузеру.
 */
class ServiceClient {

	public static function forward(string $method, string $path, ?string $body = null): array {
		if (!preg_match('#^/api/[A-Za-z0-9/_\-?=&.%]*\z#', $path) || strpos($path, '..') !== false) {
			return [400, json_encode(['error' => 'bad path'])];
		}

		// Reject ambiguous path encodings instead of trying to predict how curl, a proxy and
		// the servlet container will each decode or normalize them. Query encoding is unaffected.
		$uri_path = explode('?', $path, 2)[0];
		if (strpos($uri_path, '%') !== false || strpos($uri_path, '//') !== false
				|| preg_match('#(?:^|/)\.(?:/|$)#', $uri_path)) {
			return [400, json_encode(['error' => 'bad path'])];
		}
		if (($uri_path === '/api/evaluation/runs' || strpos($uri_path, '/api/evaluation/runs/') === 0)
				&& CWebUser::getType() < USER_TYPE_ZABBIX_ADMIN) {
			return [403, json_encode(['error' => 'forbidden'])];
		}

		$config = require __DIR__.'/../config.php';
		$ch = curl_init(rtrim($config['service_url'], '/').$path);
		$headers = [
			'Content-Type: application/json',
			'X-Advisor-Token: '.$config['service_token'],
			'X-Advisor-User: '.(CWebUser::$data['username'] ?? '')
		];
		if ($path === '/api/settings/retention' && CWebUser::getType() >= USER_TYPE_ZABBIX_ADMIN) {
			$token = getenv('INCIDENT_ADVISOR_RETENTION_TOKEN')
				?: trim((string) @file_get_contents('/advisor-admin/token'));
			if ($token !== '') {
				$headers[] = 'X-Advisor-Retention-Token: '.$token;
			}
		}
		curl_setopt_array($ch, [
			CURLOPT_CUSTOMREQUEST => $method,
			CURLOPT_RETURNTRANSFER => true,
			CURLOPT_HTTPHEADER => $headers,
			CURLOPT_TIMEOUT => $config['timeout'],
			CURLOPT_CONNECTTIMEOUT => 3
		]);
		if ($body !== null) {
			curl_setopt($ch, CURLOPT_POSTFIELDS, $body);
		}

		$response = curl_exec($ch);
		if ($response === false) {
			$error = curl_error($ch);
			curl_close($ch);
			return [502, json_encode(['error' => 'Сервис недоступен: '.$error])];
		}
		$status = curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
		curl_close($ch);

		return [$status, $response === '' ? 'null' : $response];
	}
}
