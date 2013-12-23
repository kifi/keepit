/* global mixpanel */
(function (win) {
	'use strict';

	var $ = win.jQuery,
		document = win.document;

	var EVENT_EXT_VIEW = 'viewed_external_page',
		EVENT_EXT_CLICK = 'clicked_external_page';

	// on load, track view event
	$(document).ready(function () {
		track(EVENT_EXT_VIEW);
	});

	var DEFAULT_SOURCE_VALUE = 'body';

	// on clicking on links, track click event
	$(document).on('click', 'a[href]', function () {
		var $el = $(this),
			data = $el.data(),
			trackAction = data.trackAction;
		if (trackAction) {
			track(EVENT_EXT_CLICK, {
				action: trackAction,
				source: getDataFromAncestors($el, 'trackSource', DEFAULT_SOURCE_VALUE)
			});
		}
	});

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

	function getType() {
		return $('html').data('trackType');
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
