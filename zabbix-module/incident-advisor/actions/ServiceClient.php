<?php declare(strict_types = 0);

namespace Modules\IncidentAdvisor\Actions;

use CWebUser;

/**
 * Пересылает запрос интерфейса в Java-сервис. Пропускает только пути /api/..., добавляет общий токен
 * и имя пользователя Zabbix — сервис доверяет этому прокси, а не браузеру.
 */
class ServiceClient {

	public static function forward(string $method, string $path, ?string $body = null): array {
		if (!preg_match('#^/api/[A-Za-z0-9/_\-?=&.%]*$#', $path) || strpos($path, '..') !== false) {
			return [400, json_encode(['error' => 'bad path'])];
		}

		$config = require __DIR__.'/../config.php';
		$ch = curl_init(rtrim($config['service_url'], '/').$path);
		$headers = [
			'Content-Type: application/json',
			'X-Advisor-Token: '.$config['service_token'],
			'X-Advisor-User: '.(CWebUser::$data['username'] ?? '')
		];
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
