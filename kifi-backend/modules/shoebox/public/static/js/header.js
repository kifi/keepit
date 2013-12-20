(function (win) {
	'use strict';

	var $ = win.jQuery;

	$(win).scroll(function () {
		var scrolling = $(this).scrollTop() > 0;
		$('html').toggleClass('scroll', scrolling);

		// for tracking purpose
		var source = scrolling ? 'scrollingHeader' : 'header';
		$('.kifi-header').data('trackSource', source);
	});

})(this);
