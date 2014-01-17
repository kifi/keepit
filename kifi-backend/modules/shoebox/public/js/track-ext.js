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

	// on clicking on links, track click event
	$(document).on('click', 'a[href]', function () {
		var $el = $(this),
			data = $el.data(),
			trackAction = data.trackAction;
		if (trackAction) {
			track(getClickEventName(), {
				action: trackAction,
				source: getDataFromAncestors($el, 'trackSource', DEFAULT_SOURCE_VALUE)
			});
		}
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
