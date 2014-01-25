/* global mixpanel */
(function (win) {
	'use strict';

	var $ = win.jQuery,
		document = win.document,
		$html = $('html'),
		user,
		userPromise;

	function getUserStatus() {
		var userStatus = 'standard';
		if (user && user.experiments) {
			if (user.experiments.indexOf('fake') > -1) {
				userStatus = 'fake';
			}
			else if (user.experiments.indexOf('admin') > -1) {
				userStatus = 'admin';
			}
		}
		return userStatus;
	}

	function trackEventWithId(fn, data) {
		if (user) {
			// get_distinct_id is asyncly loaded when full mixpanel API is loaded
			if (!mixpanel.get_distinct_id) {
				return win.setTimeout(function () {
					trackEventWithId(fn, data);
				}, 200);
			}
			var oldId = mixpanel.get_distinct_id();
			mixpanel.identify(user.id);
			data = data || {};
			data.userStatus = getUserStatus();
			fn(data);
			delete data.userStatus;
			mixpanel.identify(oldId);
		}
		else if (userPromise) {
			userPromise.then(function (u) {
				user = u;
				trackEventWithId(fn, data);
			});
		}
	}

	function getClickData($el, trackAction) {
		var data = {
			action: trackAction
		};

		var source = getDataFromAncestors($el, 'trackSource');
		if (source) {
			data.source = source;
		}

		return data;
	}

	var Tracker = win.Tracker = {
		setUserPromise: function (promise) {
			userPromise = promise;
		},
		setUser: function (u) {
			user = u;
		},
		trackView: function () {
			track('visitor_viewed_page');

			trackEventWithId(function (data) {
				track('user_viewed_page', data);
			});
		},
		trackClick: function (el) {
			try {
				var $el = $(el),
					trackAction = getDataFromAncestors($el, 'trackAction');
				if (trackAction) {
					track('visitor_clicked_page', getClickData($el, trackAction));

					trackEventWithId(function (data) {
						track('user_clicked_page', data);
					}, getClickData($el, trackAction));
				}
			}
			catch (e) {
				if (win.console && win.console.log) {
					win.console.log('could not track', e, e.stack);
				}
			}
		}
	};

	// on load, track view event
	$(document).ready(function () {
		Tracker.trackView();
	});

	// on clicking on links, track click event
	$(document).on('click', 'a[href], *[data-track-click]', function () {
		Tracker.trackClick(this);
	});

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
		win.console.log(name, JSON.stringify(data, null, '\t'));
		return mixpanel.track(name, data);
	}

})(this);
