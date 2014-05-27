/* global mixpanel */
(function (win) {
	'use strict';

	var $ = win.jQuery,
		document = win.document,
		$html = $('html');

	// on load, track view event
	$(document).ready(function () {
		track($html.data('trackViewEvent'));
	});

	var DEFAULT_SOURCE_VALUE = 'body';

	var Tracker = win.Tracker = {
		trackClick: function (el, event) {
			try {
				var $el = $(el),
					trackAction = getDataFromAncestors($el, 'trackAction');
				if (trackAction) {
					if (el.tagName === 'A') {
						event.preventDefault();
						track(getClickEventName(), {
							type: getType(),
							action: trackAction,
							source: getDataFromAncestors($el, 'trackSource', DEFAULT_SOURCE_VALUE)
						}, function () {
							win.location = el.href;
						});
					} else {
						track(getClickEventName(), {
							type: getType(),
							action: trackAction,
							source: getDataFromAncestors($el, 'trackSource', DEFAULT_SOURCE_VALUE)
						});
					}
				}
			}
			catch (e) {
				if (win.console && win.console.log) {
					win.console.log('could not track', e, e.stack);
				}
			}
		}
	};

	// on clicking on links, track click event
	$(document).on('click', 'a[href], *[data-track-click], *[data-track-action]', function (event) {
		Tracker.trackClick(this, event);
	});


	function getClickEventName() {
		return $html.data('trackClickEvent');
	}

	function getType() {
		return $html.data('trackType');
	}

	function getDataFromAncestors($el, name, defaultValue) {
		var value;
		while ($el && $el.length) {
			value = $el.data(name);
			if (value) {
				return value;
			}
			$el = $el.parent();
		}

		return defaultValue;
	}

	function addDefaultValues(data) {
		data = data || {};
		if (data.type == null) {
			data.type = getType();
		}
		if (data.origin == null) {
			data.origin = win.location.origin;
		}
		return data;
	}

	function track(name, data, cb) {
		if (!cb) {
			cb = $.noop;
		}
		data = addDefaultValues(data);
		win.setTimeout(function () {
			cb();
		}, 1000);

		return mixpanel.track(name, data, cb);
	}

})(this);
