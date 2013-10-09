/**
 * ----------------
 *     clickout    
 * ----------------
 *
 * clickout is a jQuery plugin that fires an 'clickout' event when user clicks outside of the element.
 *
 * @author Joon Ho Cho <joon@42go.com>
 * @date 10-08-2013
 */

(function ($ /*, win*/ ) {
	'use strict';

	var EVENT_TYPE = 'clickout',
		$DOC,
		DOC_LISTENER,
		SEL_MAP = {},
		ELEMENTS = [];

	function addDocListener() {
		if (DOC_LISTENER) {
			return false;
		}

		DOC_LISTENER = function (e) {
			var target = e.target,
				$target = $(target),
				evt = jQuery.Event('clickout');

			evt.target = target;
			evt.clickEvent = e;

			triggerElements(target, evt);
			triggerSelectors($target, evt);
		};

		$DOC = $(document);
		$DOC.click(DOC_LISTENER);

		return true;
	}

	function removeDocListener() {
		if (!DOC_LISTENER || hasListener()) {
			return false;
		}

		$DOC.off('click', DOC_LISTENER);
		$DOC = DOC_LISTENER = null;

		return true;
	}

	function addSelector(sel) {
		if (sel) {
			SEL_MAP[sel] = true;
			addDocListener();
			return true;
		}
		return false;
	}

	function removeSelector(sel) {
		if (sel) {
			delete SEL_MAP[sel];
			removeDocListener();
			return true;
		}
		return false;
	}

	function triggerSelectors($target, evt) {
		var selectors = Object.keys(SEL_MAP);
		for (var i = 0, l = selectors.length, sel; i < l; i++) {
			sel = selectors[i];
			if (!$target.closest(sel).length) {
				log('clickout', sel)();
				$(sel).trigger(evt);
			}
		}
	}

	function addElements(els) {
		for (var i = 0, l = els.length, el; i < l; i++) {
			el = els[i];
			if (ELEMENTS.indexOf(el) === -1) {
				ELEMENTS.push(el);
			}
		}
		addDocListener();
	}

	function removeElements(els) {
		for (var i = els.length - 1, el, index; i >= 0; i--) {
			el = els[i];
			index = ELEMENTS.indexOf(el);
			if (index !== -1) {
				ELEMENTS.splice(index, 1);
			}
		}
		removeDocListener();
	}

	function triggerElements(target, evt) {
		for (var i = 0, l = ELEMENTS.length, el; i < l; i++) {
			el = ELEMENTS[i];
			if (!el.contains(target)) {
				log('clickout', el)();
				$(el).triggerHandler(evt);
			}
		}
	}

	function hasListener() {
		return (ELEMENTS.length || Object.keys(SEL_MAP).length) ? true : false;
	}

	$.fn.clickout = function (method, callback) {
		var sel = this.selector,
			isCallback = typeof callback === 'function';

		switch (method) {
		case 'on':
			if (sel) {
				addSelector(sel);
			}
			else {
				addElements(this);
			}

			if (isCallback) {
				log('callback', EVENT_TYPE, callback)();
				this.on(EVENT_TYPE, callback);
			}
			break;
		case 'off':
			if (sel) {
				removeSelector(sel);
			}
			else {
				removeElements(this);
			}

			if (isCallback) {
				this.off(EVENT_TYPE, callback);
			}
			break;
		default:
			throw new Error('wrong method name. name=' + method);
		}

		return this;
	};

})(jQuery, this);
