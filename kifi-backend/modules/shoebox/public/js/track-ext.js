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
		track: track,
		trackClick: function (el) {
			try {
				var $el = $(el),
					trackAction = getDataFromAncestors($el, 'trackAction');
				if (trackAction) {
					track(getClickEventName(), {
						type: getType(),
						action: trackAction,
						source: getDataFromAncestors($el, 'trackSource', DEFAULT_SOURCE_VALUE)
					});
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
	$(document).on('click', 'a[href], *[data-track-click], *[data-track-action]', function () {
		Tracker.trackClick(this);
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

	function track(name, data) {
		data = addDefaultValues(data);
		return mixpanel.track(name, data);
	}

})(this);
