<?php declare(strict_types = 1);

// Standalone test: php ServiceClientTest.php. No Zabbix installation or service required.
namespace {
	const USER_TYPE_ZABBIX_USER = 1;
	const USER_TYPE_ZABBIX_ADMIN = 2;
	const USER_TYPE_SUPER_ADMIN = 3;

	class CWebUser {
		public static array $data = ['username' => 'test'];
		public static int $type = USER_TYPE_ZABBIX_USER;

		public static function getType(): int {
			return self::$type;
		}
	}
}

namespace Modules\IncidentAdvisor\Actions {
	// Intercept curl before any network traffic and record the forwarded request.
	const CURLOPT_CUSTOMREQUEST = 1;
	const CURLOPT_RETURNTRANSFER = 2;
	const CURLOPT_HTTPHEADER = 3;
	const CURLOPT_TIMEOUT = 4;
	const CURLOPT_CONNECTTIMEOUT = 5;
	const CURLOPT_POSTFIELDS = 6;
	const CURLINFO_RESPONSE_CODE = 7;
	$GLOBALS['forwarded'] = [];

	function curl_init($url) {
		$GLOBALS['forwarded'][] = ['url' => $url];
		return count($GLOBALS['forwarded']) - 1;
	}
	function curl_setopt_array($ch, $options) { $GLOBALS['forwarded'][$ch]['options'] = $options; }
	function curl_setopt($ch, $option, $value) { $GLOBALS['forwarded'][$ch]['options'][$option] = $value; }
	function curl_exec($ch) { return '{"ok":true}'; }
	function curl_getinfo($ch, $option) { return 200; }
	function curl_close($ch) {}

	require __DIR__.'/ServiceClient.php';

	function check_request(string $method, string $path, int $type, int $expected): void {
		\CWebUser::$type = $type;
		$before = count($GLOBALS['forwarded']);
		[$status] = ServiceClient::forward($method, $path, $method === 'POST' ? '{}' : null);
		if ($status !== $expected || count($GLOBALS['forwarded']) !== $before + ($expected === 200 ? 1 : 0)) {
			throw new \RuntimeException("$method $path type=$type: expected $expected, got $status");
		}
		if ($expected === 200) {
			$request = $GLOBALS['forwarded'][$before];
			if (substr($request['url'], -strlen($path)) !== $path
					|| $request['options'][CURLOPT_CUSTOMREQUEST] !== $method) {
				throw new \RuntimeException("Forwarded request changed: $method $path");
			}
		}
	}

	$protected = [
		'/api/evaluation/runs', '/api/evaluation/runs?limit=50&offset=0',
		'/api/evaluation/runs?', '/api/evaluation/runs/',
		'/api/evaluation/runs/123?x=%2Fapi%2Fevaluation%2Fruns',
		'/api/evaluation/runs/123/extra'
	];
	$ambiguous = [
		'/api/evaluation/%72uns', '/api/%65valuation/runs', '/api/evaluation/runs%2f123',
		'/api/evaluation/runs%252f123', '/api/evaluation%2fruns',
		'/api/evaluation%252fruns', '/api/evaluation/runs%3bfoo',
		'/api/evaluation/runs;foo', '/api/evaluation//runs', '/api//evaluation/runs',
		'/api/./evaluation/runs', '/api/evaluation/./runs', '/api/x/../evaluation/runs',
		'/api/evaluation/%2e%2e/evaluation/runs', '/api/evaluation%5cruns',
		'/api/evaluation/runs#fragment', "/api/evaluation/runs\n", 'https://example.com/api/evaluation/runs'
	];
	$ordinary = [
		'/api/incidents', '/api/incidents?query=%2Fapi%2Fevaluation%2Fruns',
		'/api/evaluation', '/api/evaluation?path=/api/evaluation/runs',
		'/api/evaluation/runs-extra', '/api/evaluation/runs2', '/api/settings/retention'
	];
	foreach (['GET', 'POST'] as $method) {
		foreach ($protected as $path) {
			check_request($method, $path, USER_TYPE_ZABBIX_USER, 403);
			check_request($method, $path, USER_TYPE_ZABBIX_ADMIN, 200);
			check_request($method, $path, USER_TYPE_SUPER_ADMIN, 200);
		}
		foreach ($ambiguous as $path) {
			check_request($method, $path, USER_TYPE_ZABBIX_USER, 400);
			check_request($method, $path, USER_TYPE_ZABBIX_ADMIN, 400);
		}
		foreach ($ordinary as $path) {
			check_request($method, $path, USER_TYPE_ZABBIX_USER, 200);
		}
	}
	print "ServiceClient tests passed\n";
}
