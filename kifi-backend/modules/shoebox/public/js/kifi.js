(function (win) {
	'use strict';

	var KF = win.KF || (win.KF = {}),
		location = win.location;

	KF.local = location.port === '9000';
	KF.origin = KF.local ? location.protocol + '//' + location.host : 'https://www.kifi.com';
	KF.xhrBase = KF.origin + '/site';
	KF.xhrBaseEliza = KF.origin.replace('www', 'eliza') + '/eliza/site';
	KF.xhrBaseSearch = KF.origin.replace('www', 'search') + '/search';
	KF.picBase = (KF.local ? '//d1scct5mnc9d9m' : '//djty7jcqog9qu') + '.cloudfront.net';

})(this);
