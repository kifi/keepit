(function (win) {
	'use strict';
	var $ = win.jQuery;

	var $modal = $('.kifi-onboarding-modal');

	$modal.on('click', '.kifi-onboarding-prev', function (e) {
		e.preventDefault();
		prev();
	});

	$modal.on('click', '.kifi-onboarding-next', function (e) {
		e.preventDefault();
		next();
	});

	$modal.on('click', '.kifi-onboarding-close', function (e) {
		e.preventDefault();
		exit();
	});

	function getPageNum() {
		return parseInt($modal.data('page'), 10);
	}

	function setPageNum(val) {
		val = parseInt(val, 10);
		var prev = getPageNum();
		if (prev !== val) {
			$modal.data('page', val);
			$modal.removeClass('page-' + prev);
			$modal.addClass('page-' + val);
		}
		return val;
	}

	function getPages$() {
		return $modal.find('.kifi-onboarding-views > *');
	}

	function getTotalPageNum() {
		return getPages$().length;
	}

	function getPage$(val) {
		return getPages$().eq(val - 1);
	}

	function updateProgressBar(val, prev, notransition) {
		var barPos,
			barPercentage;
		switch (val) {
		case 1:
			break;
		case 2:
			barPos = 1;
			barPercentage = 0.5;
			break;
		case 3:
			barPos = 1;
			barPercentage = 1;
			break;
		case 4:
			barPos = 2;
			barPercentage = 1;
			break;
		case 5:
			barPos = 3;
			barPercentage = 1;
			break;
		case 6:
			barPos = 4;
			barPercentage = 1;
			break;
		case 7:
			break;
		}
		moveProgressBar(barPos, barPercentage, notransition || prev === 1);
	}

	function moveProgressBar(val, percentage, notransition) {
		if (val) {
			var $bar = $modal.find('.kifi-onboarding-section-bar');
			var $sections = $modal.find('.kifi-onboarding-header-section');
			var $section = $sections.eq(val - 1);
			var offset = $section.position();

			if (notransition) {
				$bar.addClass('notransition');
			}

			$bar.css({
				top: (offset.top + $section.outerHeight() + 4) + 'px',
				left: offset.left + 'px'
			});
			$bar.width($section.outerWidth());

			$bar.find('.kifi-onboarding-section-progress-bar').css({
				width: ((percentage || 1) * 100) + '%'
			});

			if (notransition) {
				// invoking size calculation prevents animation
				$bar.height();
				$bar.removeClass('notransition');
			}
		}
	}

	function moveContent(val) {
		return $modal.find('.kifi-onboarding-views').css('left', (100 * (1 - val)) + '%');
	}

	function renderButtons(val) {
		var $page = getPage$(val);
		var $texts = $page.find('.kifi-onboarding-button-texts > *');
		$modal
			.find('.kifi-onboarding-buttons')
				.find('.kifi-onboarding-prev')
					.html($texts.first().html())
					.end()
				.find('.kifi-onboarding-next')
					.html($texts.last().html())
					.end();
	}

	function getPrevPageNum() {
		var val = getPageNum();
		if (val - 1 >= 1) {
			return val - 1;
		}
	}

	function getNextPageNum() {
		var val = getPageNum();
		if (val + 1 <= getTotalPageNum()) {
			return val + 1;
		}
	}

	function exit() {
		$('.kifi-onboarding-shade, .kifi-onboarding-modal').remove();
	}

	function go(val) {
		if (val) {
			var prev = getPageNum();
			setPageNum(val);
			updateProgressBar(val, prev);
			moveContent(val);
			renderButtons(val);
			return val;
		}
		return exit();
	}

	function prev() {
		return go(getPrevPageNum());
	}

	function next() {
		return go(getNextPageNum());
	}

	var resizeId;
	$(win).resize(function () {
		if (resizeId) {
			win.clearTimeout(resizeId);
		}
		resizeId = win.setTimeout(function () {
			resizeId = null;
			updateProgressBar(getPageNum(), null, true);
		}, 0);
	});

})(this);
