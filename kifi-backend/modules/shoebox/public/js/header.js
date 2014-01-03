(function (win) {
	'use strict';

	var $ = win.jQuery;

	var THRESHOLD = 50;

	$(win).scroll(function () {
		win.setTimeout(function () {
			var scrolling = $(this).scrollTop() > THRESHOLD;
			$('html').toggleClass('scroll', scrolling);

			// for tracking purpose
			var source = scrolling ? 'scrollingHeader' : 'header';
			$('.kifi-header').data('trackSource', source);
		});
	});

})(this);
