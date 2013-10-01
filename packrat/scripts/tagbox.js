// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require styles/keeper/tagbox.css

var tagbox = (function () {
	'use strict';

	var $tagbox;

	return {
		show: function ($slider) {
			render('html/keeper/tagbox', {}, function (html) {
				log('tagbox:render', $slider, html)();
				//$tagbox = $(html).appendTo($slider);
				$tagbox = $(html).appendTo($('body'));
			});
		},
		toggle: function ($slider) {
			log('tagbox:toggle')();
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

})();
