/* global mixpanel,amplitude */
(function (win) {
	'use strict';

	var $ = win.jQuery,
		document = win.document,
		$html = $('html'),
		amplitude = win.amplitude;

	var registerProperties = $html.data('trackRegister');
	// jQuery will parse the string into a JS Object if it's valid JSON
	if (registerProperties && registerProperties.constructor === Object) {
		mixpanel.register(registerProperties);
	} else if (registerProperties && win.console && win.console.error) {
		win.console.error('Invalid value of html[data-track-register] %s', registerProperties);
	}

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
				if (win.console && win.console.error) {
					win.console.error('could not track', e, e.stack);
				}
			}
		}
	};

	// on clicking on links, track click event
	$(document).on('click', 'a[href], *[data-track-click], *[data-track-action]', function () {
		Tracker.trackClick(this);
	});

	function initUser() {
		initUserCalled = true;

		var distinctId = mixpanel.get_distinct_id();
		if (amplitude && distinctId) {
			amplitude.setDeviceId(distinctId);
		}
	}

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

	function addRegisteredProperties(properties) {
		return $.extend({}, registerProperties, properties);
	}

	var eventQueue = [];
	var initUserCalled = false;

	// it's possible this function is called before mixpanel is done initializing. window.mixpanel is initially an
	// array before the javascript file is downloaded, but we don't want to record any events until
	// mixpanel is initialized and we've set the amplitude deviceId to mixpanel distinct_id property
	function track(name, data) {
		eventQueue.push({name: name, data: data});
		return processEventQueue();
	}

	function processEventQueue() {
		if (mixpanel.__loaded) {
			if (!initUserCalled) initUser();

			eventQueue.forEach(function(event) {
				var properties = addDefaultValues(event.data);
				mixpanel.track(event.name, properties);

				if (amplitude) {
					// amplitude doesn't have an equivalent to mixpanel.register so we need to add these super properties to each event
					properties = addRegisteredProperties(properties);
					amplitude.logEvent(event.name, properties);
				}
			});

			eventQueue.length = 0;
		} else {
			setTimeout(processEventQueue, 100); // try again in 100ms
		}
	}

})(this);
