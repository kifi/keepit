(function (win) {
	'use strict';

	var KF = win.KF,
		$ = win.jQuery,
		PHOTO_BINARY_UPLOAD_URL = KF.xhrBase + '/user/pic/upload',
		PHOTO_CROP_UPLOAD_URL = KF.xhrBase + '/user/pic',
		PHOTO_UPLOAD_FORM_URL = '/auth/upload-multipart-image';

	function initProfilePhotoUpload() {
		$('.profile-image-file').change(function () {
			console.log('photo selected', this.files, URL, this, arguments);
			if (this.files && URL) {
				var upload = uploadPhotoXhr2(this.files);
				if (upload) {
					// wait for file dialog to go away before starting dialog transition
					setTimeout(showLocalPhotoDialog.bind(null, upload), 200);
				}
			}
			else {
				uploadPhotoIframe(this.form);
			}
		});
		$('.profile-image, .profile-image-change').click(function (e) {
			if (e.which === 1) {
				$('.profile-image-file').click();
			}
		});
	}

	var photoXhr2;

	function uploadPhotoXhr2(files) {
		var file = Array.prototype.filter.call(files, isImage)[0];
		if (file) {
			if (photoXhr2) {
				photoXhr2.abort();
			}
			var xhr = new XMLHttpRequest();
			photoXhr2 = xhr;
			var deferred = createPhotoUploadDeferred();
			xhr.withCredentials = true;
			xhr.upload.addEventListener('progress', function (e) {
				if (e.lengthComputable) {
					deferred.notify(e.loaded / e.total);
				}
			});
			xhr.addEventListener('load', function () {
				deferred.resolve(JSON.parse(xhr.responseText));
			});
			xhr.addEventListener('loadend', function () {
				if (photoXhr2 === xhr) {
					photoXhr2 = null;
				}
				if (deferred.state() === 'pending') {
					deferred.reject();
				}
			});
			xhr.open('POST', PHOTO_BINARY_UPLOAD_URL, true);
			xhr.send(file);
			return {
				file: file,
				promise: deferred.promise()
			};
		}
	}

	function isImage(file) {
		return file.type.search(/^image\/(?:bmp|jpg|jpeg|png|gif)$/) === 0;
	}

	var iframeDeferred;

	function uploadPhotoIframe(form) {
		var $photo = $('.profile-image');
		$photo.css('background-image', 'none').addClass('unset');

		if (iframeDeferred) {
			iframeDeferred.reject(); // clean up previous in-progress upload
		}
		var deferred = iframeDeferred = createPhotoUploadDeferred()
			.always(function () {
			clearTimeout(fakeProgressTimer);
			iframeDeferred = null;
			$iframe.remove();
		});
		var $iframe = $('<iframe name=upload>').hide().appendTo('body').load(function () {
			deferred.resolve();
			var o;
			try {
				o = JSON.parse($iframe.contents().find('body').text());
			}
			catch (err) {}
			$photo.css('background-image', o ? 'url(' + o.url + ')' : '').removeClass('unset');
			$('.my-pic').css('background-image', o ? 'url(' + o.url + ')' : '');
			$(form).removeAttr('method target action');
		});
		form.method = 'POST';
		form.target = 'upload';
		form.action = PHOTO_UPLOAD_FORM_URL;
		form.submit();

		var fakeProgressTimer;
		fakeProgress(0, 100);

		function fakeProgress(frac, ms) {
			deferred.notify(frac);
			fakeProgressTimer = setTimeout(fakeProgress.bind(null, 1 - (1 - frac) * 0.9, ms * 1.1), ms);
		}
	}

	function createPhotoUploadDeferred() {
		return $.Deferred();
	}

	var localPhotoUrl;

	function showLocalPhotoDialog(upload) {
		if (localPhotoUrl) {
			URL.revokeObjectURL(localPhotoUrl);
		}
		localPhotoUrl = URL.createObjectURL(upload.file);

		photoDialog
			.show(localPhotoUrl)
			.done(function (details) {
			var $photo = $('.profile-image');
			var scale = $photo.innerWidth() / details.size;

			var $myPic = $('.my-pic');
			var myScale = $myPic.innerWidth() / details.size;

			$('.my-pic').css({
				'background-image': 'url(' + localPhotoUrl + ')',
				'background-size': myScale * details.width + 'px auto',
				'background-position': -(myScale * details.x) + 'px ' + -(myScale * details.y) + 'px'
			});

			$photo.css({
				'background-image': 'url(' + localPhotoUrl + ')',
				'background-size': scale * details.width + 'px auto',
				'background-position': -(scale * details.x) + 'px ' + -(scale * details.y) + 'px'
			})
				.removeClass('unset')
				.removeData()
				.data(details)
				.data('uploadPromise', upload.promise);

			upload.promise.always(function (image) {
				$.postJson(PHOTO_CROP_UPLOAD_URL, {
					picToken: image && image.token,
					picWidth: details.width,
					picHeight: details.height,
					cropX: details.x,
					cropY: details.y,
					cropSize: details.size
				}).always(photoDialog.hide.bind(photoDialog));
			});

		})
			.always(function () {
			$('.profile-image-file').val(null);
		});
	}

	var photoDialog = (function () {
		var $dialog, $mask, $image, $slider, deferred, hideTimer;
		var INNER_SIZE = 200;
		var SHADE_SIZE = 40;
		var OUTER_SIZE = INNER_SIZE + 2 * SHADE_SIZE;
		var SLIDER_MAX = 180;
		var dialog;

		return dialog = {
			show: function (photoUrl) {
				var img = new Image();
				img.onload = onPhotoLoad;
				img.src = photoUrl;

				$dialog = ($dialog || $('.photo-dialog').remove().css('display', '')).click(onDialogClick);
				$mask = $('.photo-dialog-mask', $dialog).mousedown(onMaskMouseDown);
				$image = $(img).addClass('photo-dialog-img').insertBefore($mask);
				$slider = $('.photo-dialog-slider', $dialog);
				clearTimeout(hideTimer);
				hideTimer = null;
				deferred = $.Deferred();

				return deferred;
			},
			setBusy: function (isBusy) {
				$dialog.toggleClass('busy', !! isBusy);
			},
			hide: function hide() {
				offEsc(hide);
				dialog.setBusy(false);
				$dialog.removeClass('photo-dialog-showing');
				hideTimer = setTimeout(function () {
					$dialog.remove();
					$image.remove();
					$mask = $image = $slider = deferred = hideTimer = null;
				}, 500);
			}
		};

		function onPhotoLoad() {
			var nw = this.width;
			var nh = this.height;
			var dMin = INNER_SIZE;
			var dMax = Math.max(OUTER_SIZE, Math.min(1000, nw, nh));
			var d0 = Math.max(INNER_SIZE, Math.min(OUTER_SIZE, nw, nh));
			var wScale = Math.max(1, nw / nh);
			var hScale = Math.max(1, nh / nw);
			var w = d0 * wScale;
			var h = d0 * hScale;
			var top = 0.5 * (OUTER_SIZE - h);
			var left = 0.5 * (OUTER_SIZE - w);
			$image
				.css({
				width: w,
				height: h,
				top: top,
				left: left
			})
				.data({
				naturalWidth: nw,
				naturalHeight: nh,
				width: w,
				height: h,
				top: top,
				left: left
			});
			$slider.slider({
				max: SLIDER_MAX,
				value: Math.round(SLIDER_MAX * (d0 - dMin) / (dMax - dMin)),
				slide: onSliderSlide.bind($image[0], $image.data(), percentToPx(dMin, dMax), wScale, hScale)
			});
			$dialog.appendTo('body').layout().addClass('photo-dialog-showing').find('.ui-slider-handle').focus();
			onEsc(dialog.hide);
		}

		function onEsc(handler) {
			onKeyDown.guid = handler.guid = handler.guid || $.guid++;
			$(document).keydown(onKeyDown);

			function onKeyDown(e) {
				if (e.which === 27) {
					handler(e);
					e.preventDefault();
				}
			}
		}

		function offEsc(handler) {
			$(document).off('keydown', handler);
		}

		function percentToPx(pxMin, pxMax) {
			var factor = (pxMax - pxMin) / SLIDER_MAX;
			return function (pct) {
				return pxMin + pct * factor;
			};
		}

		function onSliderSlide(data, pctToPx, wScale, hScale, e, ui) {
			var d = pctToPx(ui.value);
			var w = d * wScale;
			var h = d * hScale;
			var top = Math.min(SHADE_SIZE, Math.max(SHADE_SIZE + INNER_SIZE - h, data.top - 0.5 * (h - data.height)));
			var left = Math.min(SHADE_SIZE, Math.max(SHADE_SIZE + INNER_SIZE - w, data.left - 0.5 * (w - data.width)));
			this.style.top = top + 'px';
			this.style.left = left + 'px';
			this.style.width = w + 'px';
			this.style.height = h + 'px';
			data.width = w;
			data.height = h;
			data.top = top;
			data.left = left;
		}

		function onMaskMouseDown(e) {
			e.preventDefault();
			var x0 = e.screenX;
			var y0 = e.screenY;
			var data = $image.data();
			var leftMin = INNER_SIZE + SHADE_SIZE - data.width;
			var topMin = INNER_SIZE + SHADE_SIZE - data.height;
			var move = throttle(onMaskMouseMove.bind($image[0], x0, y0, data.left, data.top, leftMin, topMin, data), 10);
			document.addEventListener('mousemove', move, true);
			document.addEventListener('mouseup', onMaskMouseUp, true);
			document.addEventListener('mouseout', onMaskMouseOut, true);
			$mask.data('move', move);
			$dialog.addClass('dragging');
		}

		// from underscore.js 1.5.2 underscorejs.org

		function throttle(func, wait, options) {
			var context, args, result;
			var timeout = null;
			var previous = 0;
			options || (options = {});
			var later = function () {
				previous = options.leading === false ? 0 : Date.now();
				timeout = null;
				result = func.apply(context, args);
			};
			return function () {
				var now = Date.now();
				if (!previous && options.leading === false) {
					previous = now;
				}
				var remaining = wait - (now - previous);
				context = this;
				args = arguments;
				if (remaining <= 0) {
					clearTimeout(timeout);
					timeout = null;
					previous = now;
					result = func.apply(context, args);
				}
				else if (!timeout && options.trailing !== false) {
					timeout = setTimeout(later, remaining);
				}
				return result;
			};
		}

		function onMaskMouseMove(x0, y0, left0, top0, leftMin, topMin, data, e) {
			var left = data.left = Math.min(SHADE_SIZE, Math.max(leftMin, left0 + e.screenX - x0));
			var top = data.top = Math.min(SHADE_SIZE, Math.max(topMin, top0 + e.screenY - y0));
			this.style.left = left + 'px';
			this.style.top = top + 'px';
		}

		function onMaskMouseUp() {
			document.removeEventListener('mousemove', $mask.data('move'), true);
			document.removeEventListener('mouseup', onMaskMouseUp, true);
			document.removeEventListener('mouseout', onMaskMouseOut, true);
			$mask.removeData('move');
			$dialog.removeClass('dragging');
		}

		function onMaskMouseOut(e) {
			if (!e.relatedTarget) {
				onMaskMouseUp();
			}
		}

		function onDialogClick(e) {
			if (e.which !== 1) {
				return;
			}
			var $el = $(e.target);
			var submitted = $el.hasClass('photo-dialog-submit');
			if (submitted || $el.is('.photo-dialog-cancel,.photo-dialog-x,.photo-dialog-cell')) {
				var o = $image.data();
				if (submitted) {
					var scale = o.naturalWidth / o.width;
					dialog.setBusy(true);
					deferred.resolve({
						width: o.naturalWidth,
						height: o.naturalHeight,
						x: Math.round(scale * (SHADE_SIZE - o.left)),
						y: Math.round(scale * (SHADE_SIZE - o.top)),
						size: Math.round(scale * INNER_SIZE)
					});
				}
				else {
					dialog.hide();
					deferred.reject();
				}
			}
		}
	}());

	// end of profile photo upload
})();
