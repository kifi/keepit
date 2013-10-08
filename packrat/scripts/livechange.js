(function ($, win) {
	'use strict';

	function createValueTimeout(type, delay) {
		var lastVal = '',
			tid;

		function onTimeout() {
			tid = null;
			var $this = $(this),
				val = $this.val() || '';
			if (val !== lastVal) {
				$this.trigger({
					type: type,
					value: val,
					prevValue: lastVal
				});
				lastVal = val;
			}
		}

		return function () {
			if (tid) {
				win.clearTimeout(tid);
			}
			tid = win.setTimeout(onTimeout.bind(this), delay);
		};
	}

	$.fn.livechange = function (o) {
		o = $.extend({
			delay: 1,
			init: false,
			type: 'livechange',
			on: null
		}, o);

		var on = o.on;
		if (on) {
			var context = o.context;
			if (context != null) {
				on = on.bind(context);
			}
			this.on(o.type, on);
		}

		return this.each(function () {
			var fn = createValueTimeout(o.type, o.delay);
			$(this).on('keydown change input paste', fn);
			if (o.init) {
				fn.call(this);
			}
		});
	};
})(jQuery, this);
