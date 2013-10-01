// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require styles/keeper/tagbox.css

this.tagbox = (function (win) {
	'use strict';

	var $ = jQuery,
		$tagbox;

	return {
		show: function ($slider) {
			win.render('html/keeper/tagbox', {}, function (html) {
				win.log('tagbox:render', $slider, html)();
				//$tagbox = $(html).appendTo($slider);
				$tagbox = $(html).appendTo($('body'));
				var input = $tagbox.find('input.kifi-tagbox-input')
					.on('focus', function () {
					$(this).closest('.kifi-tagbox-input-box').addClass('focus');
				})
					.on('blur', function () {
					$(this).closest('.kifi-tagbox-input-box').removeClass('focus');
				});
			});
		},
		toggle: function ($slider) {
			win.log('tagbox:toggle')();
			if ($tagbox) {
				this.hide();
			}
			else {
				this.show($slider);
			}
		},
		hide: function () {
			$tagbox.remove();
			$tagbox = null;
		}
	};

})(this);
