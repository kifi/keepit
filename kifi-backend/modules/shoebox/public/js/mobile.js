(function (win) {
	'use strict';

	var $ = win.jQuery,
		$countdown = $('.kifi-countdown'),
		$time = $countdown.find('.kifi-time'),
		$date = $countdown.find('.kifi-date');

	var year = 2014,
		month = 3,
		day = 19,
		hour = 14;

	var relTime = new Date(Date.UTC(year, month - 1, day, hour + 8, 0, 0, 0));
	if (relTime > new Date()) {
		updateTime();
		var tid = win.setInterval(updateTime, 250);
	}

	function updateTime() {
		var now = new Date();
		var diff = relTime - now;
		if (diff < 0) {
			win.clearTimeout(tid);
		}

		var secs = Math.floor(diff / 1000);
		var sec = secs % 60;
		diff = secs - sec;
		if (sec < 10) {
			sec = '0' + sec;
		}

		var mins = Math.floor(diff / 60);
		var min = mins % 60;
		diff = mins - min;
		if (min < 10) {
			min = '0' + min;
		}

		var hours = Math.floor(diff / 60);
		var hour = hours % 24;
		diff = hours - hour;
		if (hour < 10) {
			hour = '0' + hour;
		}

		var days = Math.floor(diff / 24);

		$time.text(hour + ':' + min + ':' + sec);
		$date.text(days);
	}

	var EMAIL_REGEX = /^[^@]+@[^@]+$/;

	function verifyEmail(email) {
		if (!EMAIL_REGEX.test(email)) {
			return false;
		}
		if (email.charAt(email.length - 1) === '.') {
			return false;
		}
		return true;
	}

	$('form').on('submit', function (e) {
		e.preventDefault();
		Tracker.trackClick(this);
		var $form = $(this);
		var data = {};
		$.each($form.serializeArray(), function (i, field) {
			data[field.name] = field.value || void 0;
		});
		var email = data.email = $.trim(data.email);
		if (!verifyEmail(email)) {
			win.alert('Invalid email address');
			return;
		}

		$.ajax({
			url: '/waitlist',
			type: 'POST',
			dataType: 'text',
			contentType: 'application/json',
			data: JSON.stringify(data)
		})
		.complete(function (resp) {
			var focused = win.document.activeElement;
			if (focused && focused.blur) {
				focused.blur();
			}
			$('.kifi-added-email').text(data.email);
			$('input[name=email]').val(data.email);
			$('html').addClass('submitted');
			$('input[name=extId]').val(resp.responseText);
		});
	});

	var wistiaEmbed = win.Wistia.embed('arn4nh8il4');
	var $shadow = $('.wistia_shadow');
	var $wrapper = $shadow.find('.wistia_video_wrapper');
	var $win = $(win);

	function playVideo() {
		wistiaEmbed.play();
		wistiaEmbed.bind('end', function () {
			$shadow.removeClass('visible shown');
		});
	}

	function closeVideo() {
		wistiaEmbed.pause();
		$shadow.removeClass('shown visible');
	}

	$('.wistia_close').click(closeVideo);

	$shadow.on('click', function (e) {
		if (!$(e.target).closest('.wistia_video_wrapper').length) {
			Tracker.trackClick(e.target);
			closeVideo();
		}
	});

	$('.kifi-play').on('click', function (e) {
		e.preventDefault();
		$shadow.addClass('shown');
		var a = $shadow[0].clientHeight;
		$shadow.addClass('visible');
		resizeWistiaEmbed(true);

		if ($win.width() >= 700) {
			win.setTimeout(playVideo, 600);
		}
		else {
			playVideo();
		}
	});

	var VW = 960,
		VH = 540,
		VR = VH / VW;

	function resizeWistiaEmbed(force) {
		if (!(force || wistiaEmbed.state() === 'playing')) {
			return;
		}

		var ww = $win.width(),
			wh = $win.height();

		var vw = VW,
			vh = VH;

		if (ww < vw) {
			vw = ww * 0.9;
			vh = VR * vw;
		}

		if (wh < vh) {
			vh = wh * 0.9;
			vw = vh / VR;
		}

		wistiaEmbed.width(vw);
		wistiaEmbed.height(vh);
		$wrapper.width(vw).height(vh);
		$wrapper.css({
			'margin-left': - (vw / 2) + 'px',
			'margin-top': - (vh / 2) + 'px'
		});
	}

	$win.resize(resizeWistiaEmbed);

	$('.kifi-change-email').on('click', function (e) {
		e.preventDefault();
		$('html').removeClass('submitted');
	});

	var THRESHOLD = 50;

	$win.scroll(function () {
		win.setTimeout(function () {
			var scrolling = $(this).scrollTop() > THRESHOLD;
			$('html').toggleClass('scroll', scrolling);
		});
	});

})(this);
