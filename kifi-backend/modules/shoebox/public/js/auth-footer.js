(function (win) {
	'use strict';

	var $ = win.jQuery,
		$win = $(win),
		$main = $('main'),
		$footer = $('.kifi-footer');

	function updateFooter() {
		$('.kifi-footer').css({
			position: $win.height() <= $main.outerHeight() + $footer.outerHeight() + 5 ? 'static' : 'fixed'
		});
	}

	$win.resize(updateFooter);

	$(function () {
		updateFooter();
		$footer.css('display', 'block');
	});

})(this);
