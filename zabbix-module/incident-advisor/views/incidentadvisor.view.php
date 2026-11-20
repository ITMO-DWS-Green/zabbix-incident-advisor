<?php declare(strict_types = 0);

/**
 * @var CView $this
 * @var array $data
 */

(new CHtmlPage())
	->setTitle(_('Incident advisor'))
	->addItem(
		(new CDiv())
			->setId('incident-advisor')
			->setAttribute('data-csrf', $data['csrf_token'])
			->setAttribute('data-can-manage-retention', $data['can_manage_retention'] ? '1' : '0')
			->setAttribute('data-can-write', $data['can_write'] ? '1' : '0')
	)
	->show();
