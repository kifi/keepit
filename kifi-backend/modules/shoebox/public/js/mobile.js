(function (win) {
	'use strict';

	var $ = win.jQuery,
		$countdown = $('.kifi-countdown'),
		$time = $countdown.find('.kifi-time'),
		$date = $countdown.find('.kifi-date');

	var year = 2014,
		month = 2,
		day = 1;

	var relTime = new Date(year, month - 1, day);
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

	$('form').on('submit', function (e) {
		e.preventDefault();
		var $form = $(this);
		var data = {};
		$.each($form.serializeArray(), function (i, field) {
			data[field.name] = field.value || void 0;
		});
		$.ajax({
			url: '/waitlist',
			type: 'POST',
			dataType: 'json',
			contentType: 'application/json',
			data: JSON.stringify(data)
		})
		.complete(function (data) {
			$('.kifi-added-email').text(data.email);
			$('input[name=email]').val(data.email);
			$('html').addClass('submitted');
			// TODO
			$('input[name=extId]').val(1);
		});
	});

	var wistiaEmbed = win.Wistia.embed('arn4nh8il4');
	$('.kifi-play').on('click', function (e) {
		e.preventDefault();
		wistiaEmbed.play();
		wistiaEmbed.bind('play', function () {
			$('.wistia_embed').addClass('playing');
		});
		wistiaEmbed.bind('end', function () {
			$('.wistia_embed').removeClass('playing');
		});
	});

	$('.kifi-change-email').on('click', function (e) {
		e.preventDefault();
		$('html').removeClass('submitted');
	});

	var THRESHOLD = 50;

	$(win).scroll(function () {
		win.setTimeout(function () {
			var scrolling = $(this).scrollTop() > THRESHOLD;
			$('html').toggleClass('scroll', scrolling);
		});
	});
	
})(this);
