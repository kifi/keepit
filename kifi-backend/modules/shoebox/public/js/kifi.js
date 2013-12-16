(function (win) {
	'use strict';

	var KF = win.KF || (win.KF = {}),
		location = win.location;

	KF.local = location.port === '9000';
	KF.dev = location.host !== 'www.kifi.com';
	KF.origin = KF.local ? location.protocol + '//' + location.host : 'https://www.kifi.com';
	KF.xhrBase = KF.origin + '/site';
	KF.xhrBaseEliza = KF.origin.replace('www', 'eliza') + '/eliza/site';
	KF.xhrBaseSearch = KF.origin.replace('www', 'search') + '/search';
	KF.picBase = (KF.local ? '//d1scct5mnc9d9m' : '//djty7jcqog9qu') + '.cloudfront.net';
	KF.noOp = function () {};

	KF.logEnabled = KF.dev;
	KF.log = (function (console) {
		if (console && console.log) {
			if (console.log.apply) {
				return function () {
					if (KF.logEnabled) {
						console.log.apply(console, arguments);
					}
				};
			}
			return function () {
				if (KF.logEnabled) {
					for (var i = 0, l = arguments.length; i < l; i++) {
						console.log(arguments[i]);
					}
				}
			};
		}
		return KF.noOp;
	})(win.console);

})(this);
