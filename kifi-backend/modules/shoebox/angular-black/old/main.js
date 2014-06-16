var log = KF.log;
var xhrBase = KF.xhrBase;

var compareSearch = {
	usage: 'search',
	sensitivity: 'base'
};
var compareSort = {
	numeric: true
};

$.ajaxSetup({
	timeout: 10000,
	cache: true,
	crossDomain: true,
	xhrFields: {withCredentials: true}
});

$.timeago.settings.localeTitle = true;
$.extend($.timeago.settings.strings, {
	seconds: 'seconds',
	minute: 'a minute',
	hour: 'an hour',
	hours: '%d hours',
	month: 'a month',
	year: 'a year'
});

(function () {
	$.fn.layout = function () {
		return this.each(forceLayout);
	};

	function forceLayout() {
		this.clientHeight;
	}

	$.fn.removeText = function () {
		return this.contents().filter(isText).remove().end().end();
	};
	function isText() {
		return this.nodeType === 3;
	}

	$.fn.dialog = function () {
		var args = Array.prototype.slice.apply(arguments);
		args.unshift(dialogMethods[args[0]]);
		args[1] = null;
		return this.each($.proxy.apply($, args));
	};
	var dialogMethods = {
		show: function () {
			var $d = $(this);
			if (!this.parentNode) {
				$d.appendTo('body').layout();
			}
			$d.addClass('showing');
			$(document).on('keydown.dialog', function (e) {
				if (e.keyCode === 27 && !e.isDefaultPrevented()) {
					e.preventDefault();
					$d.dialog('hide');
				}
			});
		},
		hide: function () {
			$(this).trigger('dialog.hide');
			$(document).off('keydown.dialog');
			$(this).on('transitionend', function end(e) {
				if (e.target === this) {
					$(this).off('transitionend', end).detach();
				}
			}).removeClass('showing');
		}
	};
})();

$.postJson = function (uri, data, done) {
	return $.ajax({
		url: uri,
		type: 'POST',
		dataType: 'json',
		data: JSON.stringify(data),
		contentType: 'application/json',
		success: done || $.noop
	});
};

// detaches and reattaches nested template so it will work as an independent template
$.fn.prepareDetached = function (opts) {
	var el = this[0], p = el.parentNode, n = el.nextSibling, t;
	p.removeChild(el);
	t = Tempo.prepare(el, opts);
	p.insertBefore(el, n);
	return t;
};

$(function () {
	// bugzil.la/35168
	var $pageColInner = $('.page-col-inner'), winHeight = $(window).height();
	if ($pageColInner.get().some(function (el) {return el.offsetHeight !== winHeight; })) {
		setPageColHeights(winHeight);
		$(window).resize(setPageColHeights);
	}
	function setPageColHeights(h) {
		$pageColInner.css('height', h > 0 ? h : $(window).height());
	}

	var $leftCol = $('.left-col').resizable({
		delay: 10,
		handles: 'e',
		minWidth: 240, // should match CSS
		maxWidth: 420,
		stop: function (e, ui) {
			log('[resizable:stop] saving');
			$.postJson(xhrBase + '/user/prefs', {site_left_col_width: String($leftCol.outerWidth())}, function (data) {
				log('[prefs]', data);
			});
		},
		zIndex: 1
	});
	$leftCol.find('.ui-resizable-handle').appendTo($leftCol.find('.page-col-inner'));

	var $subtitle = $('.subtitle'), subtitleTmpl = Tempo.prepare($subtitle);
	var $checkAll = $subtitle.find('.check-all').click(function () {
		if ($checkAll.hasClass('live')) {
			var $keeps = $main.find('.keep');
			var checked = $checkAll.toggleClass('checked').hasClass('checked');
			if (checked) {
				$keeps.addClass('selected detailed');
			} else {
				$keeps.filter('.selected').removeClass('selected detailed');
			}
			updateSubtitleTextForSelection(checked ? $keeps.length : 0);
			updateKeepDetails();
		}
	}).hover(function () {
		var $text = $checkAll.next('.subtitle-text'), d = $text.data(), noun = searchResponse ? 'result' : 'Keep';
		if (d.n) {
			$checkAll.addClass('live');
			d.leaveHtml = $text.html();
			$text.html(($checkAll.hasClass('checked') ? 'Deselect ' : 'Select ') + (
				d.n == 1 ? 'the ' + noun + ' below' :
				d.n == 2 ? 'both ' + noun + 's below' :
				'all ' + d.n + ' ' + noun + 's below'));
		}
	}, function () {
		var $text = $(this).removeClass('live').next('.subtitle-text'), d = $text.data();
		if (d.leaveHtml) {
			$text.html(d.leaveHtml);
			delete d.leaveHtml;
		}
	});

	function updateSubtitleTextForSelection(numSel) {
		var $text = $checkAll.next('.subtitle-text'), d = $text.data();
		if (numSel) {
			if (!d.defText) {
				d.defText = d.leaveHtml || $text.html();
			}
			$text.html(numSel + ' ' + (searchResponse ? 'result' : 'Keep') + (numSel === 1 ? '' : 's') + ' selected');
		} else {
			$text.html(d.defText);
			delete d.defText;
		}
		delete d.leaveHtml;
	}

	$('.keep-colls,.keep-coll').removeText();
	var $fixedTitle = $('.keep-group-title-fixed');
	var $myKeeps = $('#my-keeps'), $results = $('#search-results'), keepsTmpl = Tempo.prepare($myKeeps, {escape: false})
	.when(TempoEvent.Types.ITEM_RENDER_COMPLETE, function (ev) {
		var $keep = $(ev.element).draggable(draggableKeepOpts);
		$keep.find('.pic').each(function () {
			this.style.backgroundImage = 'url(' + this.getAttribute('data-pic') + ')';
			this.removeAttribute('data-pic');
		});
		$keep.find('time').timeago();
	}).when(TempoEvent.Types.RENDER_COMPLETE, function (ev) {
		log('[$myKeeps:RENDER_COMPLETE]', ev);
		$keepSpinner.hide();

		var $titles = ev.element === $myKeeps[0] ? addGroupHeadings() : $();
		$mainKeeps.toggleClass('grouped', $titles.length > 0);
		if ($titles.length) {
			for (var sT = $mainKeeps.find('.antiscroll-inner')[0].scrollTop, i = $titles.length; i;) {
				if ($titles[--i].offsetTop <= sT) {
					$fixedTitle.text($titles.eq(i).text()).data({$titles: $titles, i: i});
					break;
				}
			}
		} else {
			$fixedTitle.empty().removeData();
		}

		mainScroller.refresh();
	});
	var draggableKeepOpts = {
		revert: 'invalid',
		handle: '.handle',
		cancel: '.keep-checkbox',
		appendTo: 'body',
		scroll: false,
		helper: function () {
			var $keep = $(this);
			if (!$keep.hasClass('selected')) {
				return $keep.clone().width($keep.css('width'));
			} else {
				var $keeps = $keep.parent().find('.keep.selected'), refTop = this.offsetTop;
				return $('<ul class=keeps-dragging>').width($keep.css('width')).height($keep.css('height'))
					.append($keeps.clone().each(function (i) {
						this.style.top = $keeps[i].offsetTop - refTop + 'px';
					}));
			}
		},
		start: function () {
			var r = $collList[0].getBoundingClientRect();
			document.addEventListener('mousemove', move);
			document.addEventListener('mouseup', up, true);
			function up() {
				document.removeEventListener('mousemove', move);
				document.removeEventListener('mouseup', up, true);
			}
			function move(e) {
				var inCol = e.pageX < r.right && e.pageX >= r.left, dy;
				if (inCol && (dy = e.pageY - r.bottom) > -10) {
					scrollTimeout = scrollTimeout || setTimeout(scroll, scrollTimeoutMs);
					scrollPx = 15 + Math.min(5, Math.round(dy * 1.5));
				} else if (inCol && (dy = e.pageY - r.top) < 10) {
					scrollTimeout = scrollTimeout || setTimeout(scroll, scrollTimeoutMs);
					scrollPx = -10 + Math.max(-10, dy);
				} else if (scrollTimeout) {
					clearTimeout(scrollTimeout);
					scrollTimeout = null;
				}
				lastPageX = e.pageX;
				lastPageY = e.pageY;
			}
			var scrollEl = $collList.find('.antiscroll-inner')[0], scrollPx, lastPageX, lastPageY;
			var scrollTimeout, scrollTimeoutMs = 100, scrollTopMax = scrollEl.scrollHeight - scrollEl.clientHeight;
			function scroll() {
				log('[scroll] px:', scrollPx);
				var top = scrollEl.scrollTop, newTop = Math.max(0, Math.min(scrollTopMax, top + scrollPx));
				if (newTop !== top) {
					scrollEl.scrollTop = newTop;
					// update cached droppable offsets
					for (var m = $.ui.ddmanager.droppables['default'], i = 0; i < m.length; i++) {
						m[i].offset = m[i].element.offset();
					}
					$(document).trigger({type: 'mousemove', pageX: lastPageX, pageY: lastPageY});
					scrollTimeout = setTimeout(scroll, scrollTimeoutMs);
				} else {
					scrollTimeout = null;
				}
			}
		}
	};

	function addGroupHeadings() {
		var today = new Date().setHours(0, 0, 0, 0), h = [];
		$myKeeps.find('time').each(function () {
			var $time = $(this);
			switch (Math.round((today - new Date($time.attr('datetime')).setHours(0, 0, 0, 0)) / 86400000)) {
			case 0:
				if (!h[0] && !(h[0] = $myKeeps.find('.keep-group-title.today')[0])) {
					h[0] = $('<li class="keep-group-title today">Today</li>').insertBefore($time.closest('.keep'))[0];
				}
				break;
			case 1:
				if (!h[1] && !(h[1] = $myKeeps.find('.keep-group-title.yesterday')[0])) {
					h[1] = $('<li class="keep-group-title yesterday">Yesterday</li>').insertBefore($time.closest('.keep'))[0];
				}
				break;
			case 2:
			case 3:
			case 4:
			case 5:
			case 6:
				if (!h[2] && !(h[2] = $myKeeps.find('.keep-group-title.week')[0])) {
					h[2] = $('<li class="keep-group-title week">Past Week</li>').insertBefore($time.closest('.keep'))[0];
				}
				break;
			default:
				if ((h[0] || h[1] || h[2]) && !(h[3] = $myKeeps.find('.keep-group-title.older')[0])) {
					h[3] = $('<li class="keep-group-title older">Older</li>').insertBefore($time.closest('.keep'))[0];
				}
				return false;
			}
		});
		log('[addGroupHeadings] h:', h);
		return $(h.filter(identity));
	}

	var $colls = $('#collections'), collTmpl = Tempo.prepare($colls).when(TempoEvent.Types.RENDER_COMPLETE, function (event) {
		collScroller.refresh();
		$colls.find('.collection').droppable(droppableCollectionOpts);
	});
	var droppableCollectionOpts = {
		accept: '.keep',
		greedy: true,
		tolerance: 'pointer',
		hoverClass: 'drop-hover',
		drop: function (event, ui) {
			addKeepsToCollection(
				$(this).data('id'),
				ui.draggable.hasClass('selected') ? $main.find('.keep.selected') : ui.draggable
			);
		}
	};

	var collOptsTmpl = $('.page-coll-opts').removeText().prepareDetached({escape: false});
	var inCollTmpl = $('.page-coll-list').removeText().prepareDetached();
	var $detail = $('.detail'), detailTmpl = Tempo.prepare($detail);

	var $keepSpinner = $('.keeps-loading');
	var $loadMore = $('.keeps-load-more').click(function () {
		if (searchResponse) {
			doSearchTimeout();
		}
		else {
			loadKeeps($myKeeps.data('collId'));
		}
	});

	var searchId;
	function doSearchTimeout() {
		if (searchId) {
			window.clearTimeout(searchId);
		}
		searchId = window.setTimeout(function () {
			searchId = null;
			doSearch(null, $query.data('filter'));
		}, 200);
	}

	var me;
	var myNetworks;
	var myPrefs;
	var myKeepsCount;
	var collections;
	var searchResponse;
	var searchTimeout;
	var lastKeep;
	var myTotal;
	var friendsTotal;
	var othersTotal;
	var prefetchedPreviewUrls = {};

	function identity(a) {
		return a;
	}

	function formatPicUrl(userId, pictureName, size) {
		return KF.picBase + '/users/' + userId + '/pics/' + size + '/' + pictureName;
	}

	function escapeHTMLContent(text) {
		return text.replace(/[&<>]/g, function (c) { return escapeHTMLContentMap[c]; });
	}
	var escapeHTMLContentMap = {'&': '&amp;', '<': '&lt;', '>': '&gt;'};

	function collIdAndName(id) {
		return {id: id, name: collections[id].name};
	}
	function updateKeepDetails() {
		hideUndo();
		var $detailed = $main.find('.keep.detailed');
		if ($detailed.length) {
			var o = {
				numKeeps: $detailed.length,
				howKept: $detailed.not('.mine').length ? null :
					$detailed.has('.keep-private.on').length === $detailed.length ? 'pri' : 'pub'
			};
			var collIds = $detailed.find('.keep-coll').map(getDataId).get();
			if ($detailed.length === 1) {
				var $keepLink = $detailed.find('.keep-title>a'), url = $keepLink[0].href;
				o.title = $keepLink.text();
				o.url = $keepLink[0].href;
				o.collections = collIds.map(collIdAndName);
				detailTmpl.render(o);
				$('.page-who-pics').append($detailed.find('.keep-who>.pic').clone());
				$('.page-who-text').html($detailed.find('.keep-who-text').html());
				var $pic = $('.page-pic'), $chatter = $('.page-chatter-messages');
				var skipImage = false;

				if (url.indexOf('://www.youtube.com/') > -1 || url.indexOf('youtu.be/') > -1) {
					var youtubeRegex = /https?:\/\/(?:[0-9A-Z-]+\.)?(?:youtu\.be\/|youtube\.com\S*[^\w\-\s])([\w\-]{11})(?=[^\w\-]|$)[?=&+%\w.-]*/i;
					var match = url.match(youtubeRegex);
					if (match && match.length == 2) {
						var vID = match[1];
						var embedHtml = '<div class="youtube"><embed src="//www.youtube.com/v/' + vID + '&rel=0&theme=light&showinfo=0&disablekb=1&modestbranding=1&controls=0&hd=1&autohide=1&color=white&iv_load_policy=3" type="application/x-shockwave-flash" allowfullscreen="true" style="width:100%; height: 100%;" allowscriptaccess="always"></embed></div>';
						$('.page-pic-special').html(embedHtml).addClass('page-pic-special-cell').show();
						$pic.hide();
						skipImage = true;
					}
				}

				if (!skipImage) {
					if (o.url in prefetchedPreviewUrls) {
						$pic.css('background-image', 'url(' + prefetchedPreviewUrls[o.url] + ')');
					}
					else {
						$.postJson(xhrBase + '/keeps/screenshot', {url: o.url}, function (data) {
							$pic.css('background-image', 'url(' + data.url + ')');
						}).fail(function () {
							$pic.find('.page-pic-soon').addClass('showing');
						});
					}
				}
				$chatter.attr({'data-n': 0, 'data-locator': '/messages'});
				$.postJson(KF.xhrBaseEliza + '/chatter', {url: o.url}, function (data) {
					$chatter.attr({
						'data-n': data.threads || 0,
						'data-locator': '/messages' + (data.threadId ? '/' + data.threadId : '')
					});
				});
			} else { // multiple keeps
				var collCounts = collIds.reduce(function (o, id) {o[id] = (o[id] || 0) + 1; return o; }, {});
				o.collections = Object.keys(collCounts).sort(function (id1, id2) {return collCounts[id1] - collCounts[id2]; }).map(collIdAndName);
				detailTmpl.render(o);
			}
			inCollTmpl.into($detail.find('.page-coll-list')[0]).render(o.collections);
			//showKeepDetails();
		} else {
			//hideKeepDetails();
		}
	}

	// profile

	$('.my-pic').click(function (e) {
		if (e.which === 1 && $('body').attr('data-view') === 'profile') {
			e.preventDefault();
			if (History.getCurrentIndex()) {
				History.back();
			} else {
				navigate('');
			}
		}
	});

	var profileTmpl = Tempo.prepare('profile-template');

	function editProfileInput($target, e) {
		if (e) {
			e.preventDefault();
		}

		var $box = $target.closest('.profile-input-box'),
			$input = $box.find('.profile-input-input');

		$box.closest('.profile-container').find('.profile-input-box.edit').each(function () {
			cancelProfileInput($(this));
		});

		activateInput($input);
	}

	function activateInput($input) {
		$input
			.each(function () {
				var $this = $(this);
				$this.data('value', $this.val());
			})
			.prop('disabled', false)
			.first().focus();
		$input.closest('.profile-input-box').addClass('edit');
	}

	function commitInput($input) {
		$input
			.each(function () {
				var $this = $(this),
					val = trimInputValue($this.val());
				$this.data('prevValue', $this.data('value'));
				$this.val(val).data('value', val);
			})
			.prop('disabled', true);
		$input.closest('.profile-input-box').removeClass('edit');
	}

	function cancelInput($input) {
		$input
			.each(function () {
				var $this = $(this),
					val = trimInputValue($this.data('value'));
				$this.val(val);
				showError($this, false);
			})
			.prop('disabled', true);
		$input.closest('.profile-input-box').removeClass('edit');
	}

	function revertAndActivateInput($input) {
		$input
			.each(function () {
				var $this = $(this);
				$this.data('value', $this.data('prevValue'));
			})
			.prop('disabled', false)
			.first().focus();
		$input.closest('.profile-input-box').addClass('edit');
	}

	function revertInput($input) {
		$input
			.each(function () {
				var $this = $(this),
					prev = $this.data('prevValue');
				$this.val(prev).data('value', prev);
			});
	}

	function saveProfileInput($target, e) {
		if (e) {
			e.preventDefault();
		}

		var $box = $target.closest('.profile-input-box'),
			$input = $box.find('.profile-input-input'),
			valid = true;

		$input.each(function () {
			var $this = $(this),
				val = trimInputValue($this.val());
			if ($input.prop('disabled')) {
				return;
			}
			if (!val && $this.is('[required]')) {
				showError($this, 'This field is required');
				valid = false;
			}
			else if ($this.attr('name') === 'email' && !validateEmailInput($this)) {
				valid = false;
			}
			else {
				showError($this, false);
			}
		});

		if (!valid) {
			return false;
		}

		commitInput($input);

		if ($input.attr('name') === 'email') {
			saveNewPrimaryEmail($input, function () {
				revertInput($input);
			});
		}
		else {
			saveProfileInfo();
		}
	}

	function cancelProfileInput($target, e) {
		if (e) {
			e.preventDefault();
		}

		var $box = $target.closest('.profile-input-box'),
			$input = $box.find('.profile-input-input');

		cancelInput($input);
	}

	function trimInputValue(val) {
		return val ? $.trim(val).replace(/\s+/g, ' ') : '';
	}

	// profile picture upload

	var URL = window.URL || window.webkitURL;
	var PHOTO_BINARY_UPLOAD_URL = xhrBase + '/user/pic/upload';
	var PHOTO_CROP_UPLOAD_URL = xhrBase + '/user/pic';
	var PHOTO_UPLOAD_FORM_URL = '/auth/upload-multipart-image';

	function initProfilePhotoUpload() {
		$('.profile-image-file').change(function () {
			log('photo selected', this.files, URL, this, arguments);
			if (this.files && URL) {
				var upload = uploadPhotoXhr2(this.files);
				if (upload) {
					// wait for file dialog to go away before starting dialog transition
					setTimeout(showLocalPhotoDialog.bind(null, upload), 200);
				}
			} else {
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
			return {file: file, promise: deferred.promise()};
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
			iframeDeferred.reject();  // clean up previous in-progress upload
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
			} catch (err) {
			}
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
				$dialog.toggleClass('busy', !!isBusy);
			},
			hide: function hide() {
				offEsc(hide);
				dialog.setBusy(false);
				$dialog.removeClass('photo-dialog-showing');
				hideTimer = setTimeout(function () {
					$dialog && $dialog.remove();
					$image && $image.remove();
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
			.css({width: w, height: h, top: top, left: left})
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
				if (!previous && options.leading === false) { previous = now; }
				var remaining = wait - (now - previous);
				context = this;
				args = arguments;
				if (remaining <= 0) {
					clearTimeout(timeout);
					timeout = null;
					previous = now;
					result = func.apply(context, args);
				} else if (!timeout && options.trailing !== false) {
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
			if (e.which !== 1) { return; }
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

	// profile email management
	//
	var PRIMARY_INDEX = 0;

	function getPrimaryEmail(emails) {
		if (!emails) {
			emails = me.emails;
		}
		return findEmail(emails, function (info) {
			return info.isPrimary;
		})[0] || emails[PRIMARY_INDEX] || null;
	}

	function getPendingPrimaryEmail(emails) {
		return findEmail(emails || me.emails, function (info) {
			return info.isPendingPrimary;
		})[0] || null;
	}

	function getPrimaryEmailAddress(emails) {
		var addr = getPrimaryEmail(emails);
		return addr && addr.address || null;
	}

	function getProfileCopy() {
		var props = {};
		for (var i in me) {
			if (me.hasOwnProperty(i)) {
				props[i] = me[i];
			}
		}
		props.emails = copyEmails(me.emails);
		return props;
	}

	function copyEmails(emails) {
		if (emails) {
			return emails.map(function (info) {
				return {
					address: info.address,
					isPrimary: info.isPrimary
				};
			});
		}
		return emails;
	}

	function findEmail(emails, callback, that) {
		var list = [];
		emails = emails || me.emails;
		for (var i = 0, l = emails.length; i < l; i++) {
			if (callback.call(that, emails[i], i, emails)) {
				list.push(emails[i]);
			}
		}
		return list;
	}

	function removeEmailInfo(emails, addr) {
		emails = emails || me.emails;
		for (var i = emails.length - 1; i >= 0; i--) {
			if (emails[i].address === addr) {
				emails.splice(i, 1);
			}
		}
	}

	function unsetPrimary(emails) {
		var primary = getPrimaryEmail(emails);
		if (primary) {
			primary.isPrimary = false;
		}
	}

	var emailChangeTmpl = Handlebars.compile($('#primary-email-change-dialog').html());
	function showEmailChangeDialog(email, submit, cancel) {
		var $dialog = $(emailChangeTmpl({
				email: email
			}))
			.appendTo(document.body)
			.on('dialog.hide', function () {
				if (cancel) {
					cancel(false);
				}
			})
			.on('click', '.dialog-cancel', function (e) {
				e.preventDefault();
				$dialog.remove();

				if (cancel) {
					cancel(false);
				}
			})
			.on('submit', 'form', function (e) {
				e.preventDefault();
				$dialog.remove();

				if (submit) {
					submit(true);
				}
			})
			.dialog('show');
	}

	function saveNewPrimaryEmail($input, cancel) {
		var email = $input.val();
		if (getPrimaryEmailAddress() === email) {
			// already primary
			return false;
		}
		getEmailInfo(email)
			.done(function (emailInfo) {
				if (emailInfo.isPrimary || emailInfo.isPendingPrimary) {
					repromiseMe();
					return;
				}
				if (emailInfo.isVerified) {
					setNewPrimaryEmail(email)();
					return;
				}
				// email is available || (not primary && not pending primary && not verified)
				showEmailChangeDialog(email, setNewPrimaryEmail(email), cancel);
			})
			.fail(function () {
				revertAndActivateInput($input);
			})
			.fail(getAddEmailErrorCallback(email, $input));
	}

	function setNewPrimaryEmail(email) {
		return function () {
			var props = getProfileCopy();
			var emails = props.emails;
			removeEmailInfo(emails, email);
			unsetPrimary(emails);
			emails.unshift({
				address: email,
				isPrimary: true
			});
			repromiseMe(props);
		};
	}

	function saveProfileInfo() {
		var props = {},
			$container = $('.profile-container');

		$container.find('.profile-input-input').each(function () {
			var $this = $(this);
			props[$this.attr('name')] = trimInputValue($this.val());
		});

		delete props.email;

		repromiseMe(props)
			.fail(function () {
				showMessage('Uh oh! Something went wrong!');
			});
	}

	function sendChangePassword(oldPass, newPass) {
		var $changePassword = $('.profile-change-password');
		$.postJson(xhrBase + '/user/password',
			{'oldPassword': oldPass, 'newPassword': newPass}
		).done(function() {
			$changePassword.find('input').val('');
			$changePassword.removeClass('opened');
			$changePassword.find('.profile-change-password-success').text('Password updated!').show().delay(5000).fadeOut();
		}).fail(function(xhr) {
			var $message = $('.profile-change-password-message');
			if (xhr.responseJSON) {
				if (xhr.responseJSON.error == 'bad_old_password') {
					$changePassword.find('input[name=old-password]').val('').focus();
					$message.text('Your current password is not correct.').addClass('error').show();
				} else if (xhr.responseJSON.error == 'bad_new_password') {
					$changePassword.find('input[name=new-password1]').val('').focus();
					$changePassword.find('input[name=new-password2]').val('');
					$message.text('Your password needs to be longer than 7 characters.').addClass('error').show();
				} else {
					$message.text('That got weird.').addClass('error').show();
				}
			} else {
				$message.text('Couldn’t submit. Try again?').addClass('error').show();
			}
		});
	}

	$(document).on('click', '.profile-change-password-title-wrapper', function (e) {
		e.preventDefault();
		var $changePassword = $(this).closest('.profile-change-password');
		$changePassword.find('.profile-change-password-success').hide();
		$changePassword.find('.profile-change-password-message').text('');
		$changePassword.find('input').val('');
		$changePassword.toggleClass('opened');
	});

	$(document).on('click', '.profile-change-password-save', function (e) {
		var $changePassword = $(this).closest('.profile-change-password');
		var $old = $changePassword.find('input[name=old-password]');
		var $new1 = $changePassword.find('input[name=new-password1]');
		var $new2 = $changePassword.find('input[name=new-password2]');
		var $message = $changePassword.find('.profile-change-password-message');
		if ($old.val().length < 7) {
			$message.text('Your current password is not correct.').addClass('error').show();
			$old.val('').focus();
		} else if($new1.val() != $new2.val()) {
			$message.text('Your new passwords do not match.').addClass('error').show();
			$new1.val('').focus();
			$new2.val('');
		} else if($new1.val().length < 7) {
			$message.text('Your password needs to be longer than 7 characters.').addClass('error').show();
			$new1.val('').focus();
			$new2.val('');
		} else if($old.val() == $new1.val()) {
			$message.text('Your new password needs to be different than your current one.').addClass('error').show();
			$new1.val('').focus();
			$new2.val('');
		} else {
			$message.hide().text('');
			sendChangePassword($old.val(), $new1.val());
		}
		$changePassword.on('keydown', function(e) {
			$message.fadeOut(100);
			$changePassword.off('keydown');
		});
	});

	var $disconnectDialog = $('.disconnect-dialog')
		.detach()
		.show()
		.on('click', '.dialog-cancel', function (e) {
			e.preventDefault();
			$disconnectDialog.dialog('hide');
		});

	var disconnectDialogTmpl = Tempo.prepare($disconnectDialog);

	function showDisconnectDialog(network) {
		disconnectDialogTmpl.render({
			url: KF.origin + '/disconnect/' + network,
			network: ucfirst(network)
		});
		$disconnectDialog.dialog('show');
	}

	// profile email contacts
	$(document).on('click', '.import-gmail', function (e) {
		e.preventDefault();
		window.location = KF.origin + '/importContacts';
	});

	function ucfirst(str) {
		return str ? str.charAt(0).toUpperCase() + str.substring(1) : '';
	}

	var gmailAccountTmpl = Handlebars.compile($('#gmail-account').html());
	function showProfile() {
		$.when(repromiseMe(), promise.myNetworks).done(function () {
			profileTmpl.render(me);

			updateMe(me);

			var $emails = $('.profile-email-accounts tbody').empty();
			$.getJSON(xhrBase + '/user/abooks').done(function (abooks) {
				(abooks || []).forEach(function (abook) {
					$emails.append(gmailAccountTmpl({
						id: abook.id,
						status: abook.state,
						email: abook.ownerEmail,
						importedCount: abook.numProcessed,
						totalCount: abook.numContacts
					}));
				});
			});

			setTimeout(function () {
				$('.profile-first-name')
					.outerWidth($('.profile-placeholder-first-name').outerWidth());
				$('.profile-last-name')
					.outerWidth($('.profile-placeholder-last-name').outerWidth());
			});

			$('body').attr('data-view', 'profile');

			$('.left-col .active').removeClass('active');

			$('.profile').on('keydown keypress keyup', function (e) {
				e.stopPropagation();
			});

			initProfilePhotoUpload();

			$('.profile-input-input').keyup(function (e) {
				switch (e.which) {
				case 13: // enter
					saveProfileInput($(this), e);
					break;
				case 27: // esc
					cancelProfileInput($(this), e);
					break;
				}
			});

			$('.profile-input-edit').click(function (e) {
				editProfileInput($(this), e);
			});

			$('.profile-input-box-name .profile-input-edit').click(function (e) {
				setTimeout(function () {
					$('.profile-first-name,.profile-last-name').css('width', '48%');
				});
			});

			$('.profile-input-save').click(function (e) {
				saveProfileInput($(this), e);
			});

			$('.profile-disconnect').click(function (e) {
				e.preventDefault();
				showDisconnectDialog($(this).closest('li').data('network'));
			});

			$('.profile .profile-networks li').each(function () {
				var $this = $(this);
				var network = $this.data('network');

				if (!network) { return; }

				var $a = $this.find('a.profile-nw');
				var networkInfo = myNetworks.filter(function (nw) {
					return nw.network === network;
				})[0];

				if (networkInfo) {
					$this.removeClass('not-connected');
					$a.attr({
						href: networkInfo.profileUrl,
						target: '_blank',
						title: 'View profile'
					});
				}
				else {
					$this.addClass('not-connected');
					$a.attr({
						href: KF.origin + '/link/' + network,
						title: 'Click to connect'
					});
				}
			});

			var profileScroller = $('.profile').antiscroll({x: false, width: '100%'}).data('antiscroll');
			$(window).resize(profileScroller.refresh.bind(profileScroller));
		});
	}

	// profile email accounts section
	$(document).on('click', '.profile-email-addresses-title-wrapper', function (e) {
		e.preventDefault();
		var $list = $(this).closest('.profile-email-addresses');
		$list.toggleClass('opened');
		$list.find('.profile-email-address-manage')
			.removeClass('add')
			.find('input').val('');
	});

	// profile email account dropdown actions
	var $openedArrow = null;

	$(document).on('click', '.profile-email-pending-cancel', function (e) {
		e.preventDefault();
		cancelPendingPrimary($(this).closest('.profile-email-address-pending').find('.profile-email-address-pending-email').text());
	});

	$(document).on('click', '.profile-email-address-item-make-primary', function (e) {
		e.preventDefault();
		makePrimary($(this).closest('.profile-email-address-item').data('email'));
	});

	$(document).on('click', '.profile-email-address-item-delete', function (e) {
		e.preventDefault();
		showEmailDeleteDialog($(this).closest('.profile-email-address-item').data('email'));
	});

	$(document).on('click', '.profile-email-address-item-arrow', function (e) {
		e.preventDefault();
		var isOpen = $(this).hasClass('opened');
		$('.profile-email-address-item-arrow').removeClass('opened');
		if (!isOpen) {
			$(this).addClass('opened');
		}
	});

	$(document).on('click', function (e) {
		if (!$(e.target).closest('.profile-email-address-item-arrow').length) {
			$('.profile-email-address-item-arrow').removeClass('opened');
		}
	});

	var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;

	$(document).on('click', '.profile-email-address-add', function (e) {
		e.preventDefault();
		var $manage = $(this).closest('.profile-email-address-manage');
		var $input = $manage.find('input');
		$input.val('');
		$manage.addClass('add');
		$input.focus();
		$input.keydown(function (e) {
			switch (e.which) {
			case 13: // enter
				submitAddEmail.call(this, e);
				break;
			case 27: // esc
				cancelAddEmail.call(this, e);
				break;
			}
		});
	});

	function validateEmailInput($input) {
		var input = $input.val();
		if (emailAddrRe.test(input)) {
			showError($input, false);
			return true;
		}
		showError($input, 'Invalid email address', 'Please enter a valid email address');
		return false;
	}

	function showError($input, header, body) {
		var $parent = $input.parent(),
			$next = $input.next(),
			hasError = $next.hasClass('input-error');
		if (header || body) {
			$input.addClass('error');
			if (hasError) {
				$next.find('.error-header').text(header || '');
				$next.find('.error-body').text(body || '');
			}
			else {
				var $error = $('<div class="input-error"><div class="error-header">' + (header || '') + '</div><div class="error-body">' + (body || '') + '</div></div>').insertAfter($input);
				$error.offset({
					top: $input.offset().top + $input.outerHeight() + 5,
					left: $input.offset().left
				});
			}
		}
		else {
			$input.removeClass('error');
			if (hasError) {
				$next.remove();
			}
		}
	}

	$(document).on('click', '.profile-email-address-unverified .profile-email-pending-resend', function (e) {
		e.preventDefault();
		var email = $('.profile-email .profile-input-input').val();
		resendVerificationEmail(email);
	});

	$(document).on('click', '.profile-email-address-pending .profile-email-pending-resend', function (e) {
		e.preventDefault();
		var email = $(this).closest('.profile-email-address-pending').find('.profile-email-address-pending-email').text();
		resendVerificationEmail(email);
	});

	$(document).on('click', '.profile-email-address-item .profile-email-pending-resend', function (e) {
		e.preventDefault();
		var email = $(this).closest('.profile-email-address-item').data('email');
		resendVerificationEmail(email);
	});

	function getEmailInfo(email) {
		return $.getJSON(xhrBase + '/user/email?email=' + email);
	}

	function resendVerificationEmail(email) {
		$.post(xhrBase + '/user/resend-verification?email=' + email);
		showVerificationAlert(email);
	}

	function cancelAddEmail(e) {
		e.preventDefault();
		closeAddEmailInput(getAddEmailInput(this));
	}

	function getAddEmailInput(that) {
		return $(that).closest('.profile-email-address-add-box').find('.profile-email-address-add-input');
	}

	function closeAddEmailInput($input) {
		$input.val('');
		$input.off('keydown');
		showError($input, false);
		$input.closest('.profile-email-address-manage').removeClass('add');
	}

	function submitAddEmail(e) {
		e.preventDefault();
		var $input = getAddEmailInput(this);
		if (validateEmailInput($input)) {
			var email = $input.val();
			getEmailInfo(email)
				.done(getAddEmailSuccessCallback(email, $input))
				.fail(getAddEmailErrorCallback(email, $input));
		}
	}

	function getAddEmailSuccessCallback(email, $input) {
		return function (emailInfo) {
			if (emailInfo.status === 'available') {
				addEmailAccount(email);
				closeAddEmailInput($input);
				showVerificationAlert(email);
			}
			else {
				showError(
					$input,
					'This email address is already added',
					'Please use another email address.'
				);
			}
		};
	}

	function getAddEmailErrorCallback(email, $input) {
		return function (jqXhr) {
			switch (jqXhr.status) {
			case 400:
				// bad format
				showError(
					$input,
					'Invalid email address',
					'Please enter a valid email address.'
				);
				break;
			case 403:
				// belongs to another user
				showError(
					$input,
					'This email address is already taken',
					'This email address belongs to another user.<br>Please enter another email address.'
				);
				break;
			}
		};
	}

	$(document).on('click', '.profile-email-address-add-save', submitAddEmail);

	function addEmailAccount(email) {
		var props = getProfileCopy();
		var emails = props.emails;
		emails.push({
			address: email,
			isPrimary: false
		});

		return $.postJson(xhrBase + '/user/me', props).done(updateMe);
	}

	function makePrimary(email) {
		if (email) {
			var props = getProfileCopy();
			var emails = props.emails;
			unsetPrimary(emails);
			findEmail(emails, function (info) {
				if (info.address === email) {
					info.isPrimary = true;
				}
			});
			return $.postJson(xhrBase + '/user/me', props).done(updateMe);
		}
	}

	function cancelPendingPrimary(email) {
		if (email) {
			return deleteEmailAccount(email);
		}
	}

	function deleteEmailAccount(email) {
		if (email) {
			var props = getProfileCopy();
			var emails = props.emails;
			removeEmailInfo(emails, email);

			return $.postJson(xhrBase + '/user/me', props).done(updateMe);
		}
	}

	var verificationAlertTmpl = Handlebars.compile($('#verification-alert').html());
	function showVerificationAlert(email) {
		var $dialog = $(verificationAlertTmpl({
				email: email
			}))
			.appendTo(document.body)
			.on('click', '.dialog-cancel', function (e) {
				e.preventDefault();
				$dialog.remove();
			})
			.dialog('show');
	}

	var emailDeleteTmpl = Handlebars.compile($('#email-delete-dialog').html());
	function showEmailDeleteDialog(email) {
		var $dialog = $(emailDeleteTmpl({
				email: email
			}))
			.appendTo(document.body)
			.on('click', '.dialog-cancel', function (e) {
				e.preventDefault();
				$dialog.remove();
			})
			.on('submit', 'form', function (e) {
				e.preventDefault();
				deleteEmailAccount(email);
				$dialog.remove();
			})
			.dialog('show');
	}


	// Friends Tabs/Pages

	var $friends = $('.friends');
	var $friendsTabs = $friends.find('.friends-tabs>a');
	var $friendsTabPages = $friends.find('.friends-page');

	function showFriends(path) {
		path = normalizePath(path);
		$('body').attr('data-view', 'friends');
		$('.left-col .active').removeClass('active');
		$('.my-friends').addClass('active');
		selectFriendsTab(path);
	}

	function normalizePath(path) {
		if (path) {
			return path.replace(/^[\s\/]+|[\s\/]+$/g, '');
		}
		return '';
	}

	var FRIENDS_PARENT_PATHS = {
		'friends/find': 'friends/invite',
		'friends/invite/facebook': 'friends/invite',
		'friends/invite/linkedin': 'friends/invite',
		'friends/invite/email': 'friends/invite'
	};

	var FRIENDS_PREPS = {
		'friends': prepFriendsTab,
		'friends/invite': showAddFriends,
		'friends/invite/facebook': showAddFriends,
		'friends/invite/linkedin': showAddFriends,
		'friends/invite/email': showAddFriends,
		'friends/find': showAddFriends,
		'friends/requests': prepRequestsTab
	};

	function selectFriendsTab(path) {
		var pPath = FRIENDS_PARENT_PATHS[path] || path,
		$tab = $friendsTabs.filter('[data-href="' + pPath + '"]');
		log('[selectFriendsTab]', path, pPath, $tab);
		if ($tab.length) {
			$tab.removeAttr('href');
			$friendsTabs.not($tab).filter(':not([href])').each(function () {
				this.href = $(this).data('href');
			});

			$friendsTabPages.hide();
			$friendsTabPages.filter('[data-href="' + pPath + '"]').show().find('input').focus();

			FRIENDS_PREPS[path](path);
		}
		else {
			navigate('friends', {replace: true});
		}
	}

	var $addFriendsTabs = $friends.find('.add-friends-tabs>a');
	var $addFriendsTabPages = $friends.find('.add-friends-page');

	function showAddFriends(path) {
		var pPath;
		if (/^friends\/invite/.test(path)) {
			pPath = 'friends/invite';
		}
		else {
			pPath = path;
		}
		var $tab = $addFriendsTabs.filter('[data-href="' + pPath + '"]');
		log('[showAddFriends]', path, pPath, $tab);
		$nwFriendsLoading.hide();
		if ($tab.length) {
			$tab.removeAttr('href');
			$addFriendsTabs.not($tab).filter(':not([href])').each(function () {
				this.href = $(this).data('href');
			});
			$addFriendsTabPages.hide();
			$addFriendsTabPages.filter('[data-href="' + pPath + '"]').show().find('input').focus();

			var match = path.match(/^friends\/invite(\/\w*)?$/);
			if (match) {
				var network = (match[1] || '').replace(/\//g, '');
				filterFriendsByNetwork(network);
			}
			else {
				prepFindTab();
			}
		}
		else {
			navigate('friends', {replace: true});
		}
	}

	function chooseNetworkFilterDOM(network) {
		if (!network) {
			network = '';
		}

		var $filters = $('.invite-filters');
		if (network) {
			$filters.attr('data-nw-selected', network);
		}
		else {
			$filters.removeAttr('data-nw-selected');
		}

		$('.invite-filter').attr('placeholder', 'Type a name' + (network === 'email' ? ' or email' : ''));

		$filters.children('a').each(function () {
			var $this = $(this),
			nw = $this.data('nw');
			if (nw === network || (!nw && !network)) {
				$this.removeAttr('href');
			}
			else {
				$this.attr('href', $this.data('href'));
			}
		});

		$nwFriends.find('ul').empty();
	}

	function getOpt(opts, name, def) {
		var val = opts && opts[name];
		return val == null ? def : val;
	}

	function openPopup(url, name, opts) {
		var w = getOpt(opts, 'width', 880),
		h = getOpt(opts, 'height', 460),
		top = getOpt(opts, 'top', (window.screenTop || window.screenY || 0) + Math.round(0.5 * (window.innerHeight - h))),
		left = getOpt(opts, 'left', (window.screenLeft || window.screenX || 0) + Math.round(0.5 * (window.innerWidth - w))),
		dialog = getOpt(opts, 'dialog', true) ? 'yes' : 'no',
		menubar = getOpt(opts, 'menubar', false) ? 'yes' : 'no',
		resizable = getOpt(opts, 'resizable', true) ? 'yes' : 'no',
		scrollbars = getOpt(opts, 'scrollbars', false) ? 'yes' : 'no',
		status = getOpt(opts, 'status', false) ? 'yes' : 'no';

		window.open(url, name,
					'width=' + w + ',height=' + h +
					',top=' + top + ',left=' + left +
					',dialog=' + dialog + ',menubar=' + menubar +
					',resizable=' + resizable +
					',scrollbars=' + scrollbars + ',status=' + status);
	}

	function submitForm(path) {
		$('<form method=POST action="' + KF.origin + path + '">')
		.appendTo('body')
		.submit()
		.remove();
	}

	var VENDOR_NAMES = {
		facebook: 'Facebook',
		linkedin: 'LinkedIn',
		gmail: 'Gmail',
		email: 'Gmail'
	};
	var VENDOR_FRIEND_NAME = {
		facebook: 'friend',
		linkedin: 'connection',
		gmail: 'contact',
		email: 'contact'
	};

	var friendsHelpTmpl = Handlebars.compile($('#invite-friends-help').html());
	var friendsImportingTmpl = Handlebars.compile($('#friends-importing').html());

	function toggleInviteHelp(network, show, error) {
		var $cont = $('.invite-friends-help-container');
		if (show && /^facebook|linkedin|email|gmail$/.test(network)) {
			$cont.html(friendsHelpTmpl({
				'network_class': network,
				network: VENDOR_NAMES[network],
				friends: VENDOR_FRIEND_NAME[network] + 's'
			}));
			$cont.toggleClass('error', !!error);
			$cont.show();
		}
		else {
			$cont.hide();
		}
	}

	function toggleImporting(network, show, progress, from) {
		var $cont = $('.invite-friends-importing');
		if (show && /^facebook|linkedin|email|gmail$/.test(network)) {
			$cont.html(friendsImportingTmpl({
				'network_class': network,
				network: from || VENDOR_NAMES[network],
				friends: VENDOR_FRIEND_NAME[network] + 's',
				progress: progress || ''
			}));
			$cont.show();
		}
		else {
			$cont.hide();
		}
	}

	$('.invite-friends-help-container').on('click', '.invite-friends-help-connect', function () {
		connectSocial($(this).data('network'));
	});

	$('.have-no-networks').on('click', '.connect-network-button', function (e) {
		connectSocial($(e.target).data('network'));
	});

	var $refreshNetworks = $('.refresh-networks');
	$refreshNetworks.on('click', '.network-refresh-button', function (e) {
		connectSocial($(e.target).data('network'));
	});

	function connectSocial(network) {
		log('[connectSocial]', network);
		toggleInviteHelp(network, false);
		toggleImporting(network, !!network && network === getNetworkFilterSelected());

		window.location = KF.origin + (network === 'email' ? '/importContacts' : '/link/' + network);
	}

	var ABOOK_ID_TO_CALLBACK = {};

	function updateABookProgress(id, status, total, progress) {
		// notAvail, pending, processing, active
		if (ABOOK_ID_TO_CALLBACK[id]) {
			ABOOK_ID_TO_CALLBACK[id].apply(this, arguments);
		}
	}
	window.updateABookProgress = updateABookProgress;

	function createHiddenIframe(url) {
		var iframe = document.body.appendChild(document.createElement('iframe'));
		iframe.style.display = 'none';
		iframe.contentWindow.location.href = url;
		return iframe;
	}

	function getAbookProgressUpdates(id, callback, opts) {
		var deferred = $.Deferred();

		ABOOK_ID_TO_CALLBACK[id] = function (id, status, total, progress) {
			ABOOK_ID_TO_CALLBACK[id] = callback;
			deferred.resolve({
				id: id,
				status: status,
				total: total,
				progress: progress
			});
			callback(id, status, total, progress);
		};

		var iframe = createHiddenIframe(xhrBase + '/user/abookUploadStatus?id=' + id + (opts || ''));

	/*
	// these are needed for testing
	if (KF.local) {
		ABOOK_ID_TO_CALLBACK[id] = null;
		'notAvail,pending,processing,error,active'.split(',').forEach(function (data, i) {
			setTimeout(function () {
				if (!iframe) {
					return;
				}
				if (deferred) {
					deferred.resolve({
						id: id,
						status: data,
						total: 1000,
						progress: Math.floor(Math.random() * 1000)
					});
					deferred = null;
				}
				if (callback) {
					callback(id, data, 1000, Math.floor(Math.random() * 1000));
				}
			}, 1000 * (i + 1));
		})
	}
	*/

		return {
			promise: deferred.promise(),
			end: function () {
				if (iframe) {
					iframe.parentNode.removeChild(iframe);
					iframe = deferred = ABOOK_ID_TO_CALLBACK[id] = null;
				}
			}
		};
	}

	var IMPORT_CHECK = 'updateImportCheck';

	function getNetworkImportUpdates(network, callback) {
		var deferred = $.Deferred();
		window[IMPORT_CHECK] = function (data) {
			log('[' + IMPORT_CHECK + ']', data);
			window[IMPORT_CHECK] = function (data) {
				callback(String(data));
			};
			data = String(data);
			deferred.resolve(data);
			callback(data);
		};

		var iframe = createHiddenIframe(xhrBase + '/user/import-check/' + network + '?callback=parent.' + IMPORT_CHECK);

		/*
		// these are needed for testing
		if (KF.local) {
			window[IMPORT_CHECK] = null;
			'fetching,import_connections,error,finished,end'.split(',').forEach(function (data, i) {
				setTimeout(function () {
					if (!iframe) {
						return;
					}
					if (deferred) {
						deferred.resolve(data);
						deferred = null;
					}
					if (callback) {
						callback(data);
					}
				}, 1000 * (i + 1));
			})
		}
		*/

		return {
			promise: deferred.promise(),
			end: function () {
				if (iframe) {
					iframe.parentNode.removeChild(iframe);
					iframe = deferred = window[IMPORT_CHECK] = null;
				}
			}
		};
	}

	function isConnected(networks, network) {
		return networks.some(function (netw) {
			return netw.network === network;
		});
	}


	function isImporting(status) {
		return status === 'fetching' || status === 'import_connections';
	}

	var prevImportUpdate = null;

	function endImportUpdate(importUpdate) {
		if (importUpdate) {
			if (prevImportUpdate === importUpdate) {
				prevImportUpdate = null;
			}
			importUpdate.end();
		}
		else if (prevImportUpdate) {
			prevImportUpdate.end();
			prevImportUpdate = null;
		}
	}

	function isAbookFailed(state) {
		return (/^error|upload_failure$/).test(state);
	}

	function filterFriendsByNetwork(network) {
		network = network || '';

		endImportUpdate();

		$nwFriendsLoading.hide();
		$nwFriends.find('.no-results').empty().hide();

		clearNotAuthed();

		log('[filterFriendsByNetwork]', network);
		chooseNetworkFilterDOM(network);
		var isEmail = network === 'email',
		isSocial = /^facebook|linkedin$/.test(network);

		if (network) {
			$friendsTabPages.removeClass('no-networks');
		}

		if (isEmail) {
			$nwFriendsLoading.show();

			$.getJSON(xhrBase + '/user/abooks').done(function (abooks) {
				$nwFriendsLoading.hide();

				var hasAbook = Boolean(abooks && abooks.length);
				var id, email, error;
				var importing = hasAbook && abooks.some(function (abook) {
					if (KF.local) {
						id = abook.id;
						email = abook.ownerEmail;
					}
					error = isAbookFailed(abook.state);
					if (!error && abook.state !== 'active') {
						id = abook.id;
						email = abook.ownerEmail;
						return true;
					}
				}) || !!(id && KF.local);

				toggleInviteHelp(network, !(hasAbook || importing), error);
				toggleImporting(network, importing, null, email);

				log('[email import status] network=' + network + ', hasAbook=' + hasAbook + ', importing=' + importing);

				if (importing) {
					var importUpdate = getAbookProgressUpdates(id, function (id, status, total, progress) {
						log('getAbookProgressUpdates', id, status, total, progress);
						switch (status) {
						case 'active':
							toggleImporting(network, false, null, email);
							emptyAndPrepInvite(network);
							endImportUpdate(importUpdate);
							break;
						case 'upload_failure':
						case 'error':
							toggleImporting(network, false, null, email);
							toggleInviteHelp(network, true, true);
							endImportUpdate(importUpdate);
							break;
						default:
							var text = '';
							text += progress > 0 ? progress : '0';
							if (total > 0) {
								text += ' out of ' + total;
							}
							toggleImporting(network, true, text, email);
							break;
						}
					});
					prevImportUpdate = importUpdate;
				}
				else {
					if (hasAbook) {
						emptyAndPrepInvite(network);
					}
				}
			});
		}
		else if (isSocial) {
			var importUpdate = getNetworkImportUpdates(network, function (status) {
				log('getNetworkImportUpdates', status);
				if (!isImporting(status)) {
					log('getNetworkImportUpdates:end');
					toggleImporting(network, false);
					endImportUpdate(importUpdate);
					if (status === 'finished' || status === 'end') {
						emptyAndPrepInvite(network);
					}
					if (status === 'error') {
						toggleInviteHelp(network, true, true);
					}
				}
			});

			prevImportUpdate = importUpdate;
			toggleImporting(network, false);
			toggleInviteHelp(network, false);
			$nwFriendsLoading.show();

			$.when($.getJSON(xhrBase + '/user/networks'), importUpdate.promise).done(function (networkResult, status) {
				log('networks promise', networkResult, status);

				$nwFriendsLoading.hide();

				log('[networks status]', networkResult, status);

				var networks = networkResult[0];
				var connected = isConnected(networks, network);
				var importing = isImporting(status);

				log('[network status] network=' + network + ', connected=' + connected + ', importing=' + importing, !(connected || importing), !importing && connected, status === 'error');

				toggleInviteHelp(network, !(connected || importing), status === 'error');
				toggleImporting(network, importing);

				if (!importing) {
					if (connected) {
						emptyAndPrepInvite(network);
					}
					endImportUpdate(importUpdate);
				}
			});
		}
		else {
			emptyAndPrepInvite(network);
		}
	}

	function emptyAndPrepInvite(network) {
		var curNetwork = $('.invite-filters').attr('data-nw-selected') || '';
		if ((network || '') === curNetwork) {
			$nwFriends.find('ul').empty();
			$nwFriends.find('.no-results').empty().hide();
			prepInviteTab();
		}
	}

	// All kifi Friends

	var $friendsFilter = $('.friends-filter').on('input', function () {
		var val = $.trim(this.value);
		if (val) {
			var prefixes = val.toLowerCase().split(/\s+/);
			$friendsList.find('.friend').filter(function () {
				var $f = $(this),
					o = $f.data('o'),
					names = $.trim(o.firstName + ' ' + o.lastName).toLowerCase().split(/\s+/);
				$f.toggleClass('no-match', !prefixes.every(function (p) {
					return names.some(function (n) {
						return 0 === p.localeCompare(n.substring(0, p.length), undefined, compareSearch);
					});
				}));
			});
		} else {
			$friendsList.find('.no-match').removeClass('no-match');
		}
	});

	var $friendsList = $('#friends-list').antiscroll({x: false, width: '100%'})
	.on('mouseover', '.friend-status', function () {
		var $a = $(this),
			o = $a.closest('.friend').data('o');
		$(this).nextAll('.friend-action-desc').text({
			unfriended: 'Add ' + o.firstName + ' as a friend',
			requested: 'Cancel friend request',
			'': 'Unfriend ' + o.firstName
		}[o.state]);
	}).on('click', '.friend-status', function () {
		var $a = $(this),
			$friend = $a.closest('.friend'),
			o = $friend.data('o'),
			xhr;
		switch (o.state) {
		case 'unfriended':
			xhr = $.post(xhrBase + '/user/' + o.id + '/friend', function (data) {
				o.state = data.acceptedRequest ? '' : 'requested';
			});
			break;
		case 'requested':
			xhr = $.post(xhrBase + '/user/' + o.id + '/cancelRequest', function (data) {
				o.state = 'unfriended';
			}).fail(function () {
				if (xhr && xhr.responseText && JSON.parse(xhr.responseText).alreadyAccepted) {
					o.state = 'friend';
				}
			});
			break;
		default:
			showUnfriendDialog({
				id: o.id,
				name: $friend.find('.friend-name').text()
			}, function (data) {
				o.state = 'unfriended';
				(getAlwaysCallback($a, o))();
			});
		}
		if (xhr) {
			xhr.always(getAlwaysCallback($a, o));
		}
	}).on('mouseover', '.friend-mute', function () {
		var $a = $(this), o = $a.closest('.friend').data('o');
		$a.nextAll('.friend-action-desc').text(o.searchFriend ?
			'Don’t show ' + o.firstName + '’s keeps in my search results' :
			'Show ' + o.firstName + '’s keeps in my search results');
	}).on('click', '.friend-mute[href]', function () {
		var $a = $(this), o = $a.closest('.friend').data('o'), mute = !!o.searchFriend;
		$.post(mute ?
				xhrBase + '/user/' + o.id + '/exclude' :
				xhrBase + '/user/' + o.id + '/include', function () {
			o.searchFriend = !mute;
			$a.removeAttr('href').toggleClass('muted', mute).nextAll('.friend-action-desc').text('');
		});
	}).on('mouseout', '.friend-status,.friend-mute', function () {
		$(this).attr('href', 'javascript:').nextAll('.friend-action-desc').empty();
	});

	function getAlwaysCallback($a, o) {
		return function () {
			log('getAlwaysCallback', $a, o.state);
			$a.removeAttr('href').closest('.friend-actions').removeClass('requested unfriended').addClass(o.state);
			$a.closest('.friend').removeClass('requested unfriended').addClass(o.state);
			$a.nextAll('.friend-action-desc').text('');
		};
	}

	$friendsList.find('.antiscroll-inner').scroll(function () {
		$friendsList.prev().toggleClass('scrolled', this.scrollTop > 0);
	});

	var friendsScroller = $friendsList.data('antiscroll');
	$(window).resize(friendsScroller.refresh.bind(friendsScroller));

	var friendsTmpl = Tempo.prepare($friendsList).when(TempoEvent.Types.ITEM_RENDER_COMPLETE, function (ev) {
		var o = ev.item, $f = $(ev.element).data('o', o), url;
		for (var nw in o.networks) {  // TODO: move networks to template (Tempo seems broken)
			if (o.networks[nw].connected && (url = o.networks[nw].profileUrl)) {
				$f.find('.friend-nw-' + nw).attr('href', url);
			}
		}
	}).when(TempoEvent.Types.RENDER_COMPLETE, function () {
		$friendsLoading.hide();
		friendsScroller.refresh();
	});

	var $friendsLoading = $('.friends-loading');
	function prepFriendsTab() {
		$('.friends-filter').val('');
		friendsTmpl.clear();
		$friendsLoading.show();
		$.when(
			$.getJSON(xhrBase + '/user/friends'),
			$.getJSON(xhrBase + '/user/outgoingFriendRequests'))
		.done(function (a0, a1) {
			var friends = a0[0].friends, requests = a1[0];
			var requested = requests.reduce(function (o, u) {o[u.id] = true; return o; }, {});

			$friendsTabPages.toggleClass('no-friends', !friends.length);

			log('[prepFriendsTab] friends:', friends.length, 'req:', requests.length);
			for (var f, i = 0; i < friends.length; i++) {
				f = friends[i];
				f.picUri = formatPicUrl(f.id, f.pictureName, 200);
				f.state = requested[f.id] ? 'requested' : f.unfriended ? 'unfriended' : '';
				delete f.unfriended;
			}
			friends.sort(function (f1, f2) {
				return f1.firstName.localeCompare(f2.firstName, undefined, compareSort) ||
					f1.lastName.localeCompare(f2.lastName, undefined, compareSort) ||
					f1.id.localeCompare(f2.id, undefined, compareSort);
			});
			friendsTmpl.render(friends);
		})
		.always(function () {
			$friendsLoading.hide();
		});
	}

	// Friend Invites
	var FETCH_SIZE = 40;

	var $nwFriends = $('.invite-friends').antiscroll({x: false, width: '100%'});
	$nwFriends.find('.antiscroll-inner').scroll(function () { // infinite scroll
		var sT = this.scrollTop, sH = this.scrollHeight;
		// tweak these values as desired
		var offset = sH / 3, toFetch = FETCH_SIZE;
		if (!$nwFriendsLoading.is(':visible') && this.clientHeight + sT > sH - offset) {
			log('loading more friends');
			prepInviteTab(toFetch);
		}
	});
	var nwFriendsScroller = $nwFriends.data('antiscroll');
	$(window).resize(nwFriendsScroller.refresh.bind(nwFriendsScroller));  // TODO: throttle, and only bind while visible
	var nwFriendsTmpl = Tempo.prepare($nwFriends).when(TempoEvent.Types.RENDER_COMPLETE, function () {
		$nwFriendsLoading.hide();
		nwFriendsScroller.refresh();
	});
	var $nwFriendsLoading = $('.invite-friends-loading');
	var noResultsTmpl = Handlebars.compile($('#no-results-template').html());
	var noResultsTmplEmail = Handlebars.compile($('#no-results-template-email').html());

	var friendsTimeout;
	function filterFriends() {
		clearTimeout(friendsTimeout);
		$nwFriends.find('ul').empty();
		friendsTimeout = setTimeout(prepInviteTab, 200);
	}


	var friendsToShow = FETCH_SIZE;
	var friendsShowing = [];
	var moreFriends = true;
	var inviteEmailTemplate = Handlebars.compile($('#invite-email-tpl').html());

	function alignInviteFriends() {
		var $ul = $('.invite-friends > * > ul');
		$ul.toggleClass('center', $ul.children().length === 1);
	}

	function clearNotAuthed() {
		$refreshNetworks.removeClass('email gmail facebook linkedin');
	}


	function prepInviteTab(moreToShow) {
		log('[prepInviteTab]', moreToShow);
		if (moreToShow && !moreFriends) {
			return;
		}
		moreFriends = true;

		var network = getNetworkFilterSelected();
		var search = getNetworkFilterText();

		toggleInviteHelp(network, false);
		toggleImporting(network, false);

		$nwFriendsLoading.show();

		var limit = moreToShow || friendsToShow;
		var lastValue = getLast(friendsShowing);
		lastValue = lastValue && lastValue.value;

		var opts = {
			limit: limit,
			after: moreToShow ? lastValue : void 0,
			search: search,
			network: network
		};

		log('[prepInviteTab]', opts);

		if (!network) {
			getNetworks();
		}

		repromiseMe().done(function (me) {
			clearNotAuthed();
			if (me.notAuthed && me.notAuthed.length) {
				if (network) {
					if (me.notAuthed.indexOf(network) !== -1) {
						$refreshNetworks.addClass(network);
					}
				}
				else {
					$refreshNetworks.addClass(me.notAuthed.join(' '));
				}
			}
		});

		$.getJSON(xhrBase + '/user/socialConnections', opts, function (friends) {
			log('[prepInviteTab] search: ' + search + ', network: ' + network + ', friends: ', friends);
			friends.forEach(normalizeFriend);

			var nw = getNetworkFilterSelected();
			var filter = getNetworkFilterText();

			if (filter !== search || nw !== network) {
				return;
			}

			if (!moreToShow) {
				clearInviteFriends();
			}

			if (friends.length < limit) {
				moreFriends = false;

				if (!network) {
					opts.network = 'email';
					opts.after = void 0;
					$.getJSON(xhrBase + '/user/socialConnections', opts, function (friends) {
						log('[prepInviteTab2] search: ' + search + ', network: ' + network + ', friends: ', friends);
						friends.forEach(normalizeFriend);

						var nw = getNetworkFilterSelected();
						var filter = getNetworkFilterText();

						if (filter !== search || nw !== network) {
							return;
						}

						if (friends.length < limit) {
							moreFriends = false;
						}

						friendsShowing.push.apply(friendsShowing, friends);

						$nwFriends.find('.invite-email').remove();
						showInviteEmailAddress($nwFriends, search, friends);

						var $noResults = $nwFriends.find('.no-results').empty().hide();
						if (!friendsShowing.length) {
							showNoSearchInviteResults($noResults, search, network);
						}

						nwFriendsTmpl.append(friends);
						alignInviteFriends();
					});
				}
			}

			friendsShowing.push.apply(friendsShowing, friends);

			$nwFriends.find('.invite-email').remove();
			if ((!network || network === 'email') && search) {
				showInviteEmailAddress($nwFriends, search, friends);
			}

			var $noResults = $nwFriends.find('.no-results').empty().hide();
			if (network && !friendsShowing.length) {
				showNoSearchInviteResults($noResults, search, network);
			}

			nwFriendsTmpl.append(friends);
			alignInviteFriends();
		})
		.always(function () {
			$nwFriendsLoading.hide();
		});
	}

	function getLast(arr, def) {
		return arr && arr.length ? arr[arr.length - 1] : def;
	}

	function getNetworkFilterSelected(def) {
		return $('.invite-filters').attr('data-nw-selected') || def;
	}

	function getNetworkFilterText(def) {
		return $('.invite-filter').val() || def;
	}

	function clearInviteFriends() {
		nwFriendsTmpl.clear();
		friendsShowing.length = 0;
	}

	function normalizeFriend(obj) {
		obj.label = obj.label || '';
		obj.image = obj.image || '';
		obj.status = obj.status || '';

		var val = obj.value;
		var network = val && val.substring(0, val.indexOf('/'));
		obj.network = network;
		var isEmail = network === 'email';
		obj.email = isEmail && val.substring(val.indexOf('/') + 1);

		var description;
		switch (obj.status) {
		case 'joined':
			description = 'Joined Kifi';
			break;
		case 'invited':
			description = 'Invited';
			break;
		default:
			if (isEmail) {
				description = 'Invite via email';
			}
			else {
				description = 'A friend on ' + network;
			}
		}
		obj.description = description;
	}

	function hasFriendWithEmail(friends, email) {
		return friends.some(function (f) {
			return f.email === email;
		});
	}

	function showInviteEmailAddress($nwFriends, search, friends) {
		if (/^[^@]+@[^@]+$/.test(search) && !hasFriendWithEmail(friends, search)) {
			var $inviteEmail = $(inviteEmailTemplate({
				email: search
			})).appendTo($nwFriends.find('ul'))
				.find('.invite-email-link')
				// '' is necessary as third parameter
				.click(function (e) {
					var email = $(e.target).closest('.invite-email').data('email');
					openInviteDialog('email/' + email, '');
				});
		}
	}

	function showNoSearchInviteResults($noResults, search, network) {
		var html;
		if (network === 'email') {
			html = noResultsTmplEmail({ filter: search, network: network });
		}
		else {
			html = noResultsTmpl({ filter: search, network: network });
		}

		$noResults.html(html).show();
		$noResults.find('.refresh-friends').click(function (e) {
			submitForm('/friends/invite/refresh');
			$(this).removeAttr('href').text('Now refreshing... This may take a few minutes. Please try your search again later.');
		});
	}

	var $inviteMessageDialog = $('.invite-message-dialog').detach().show()
	.on('submit', 'form', function (e) {
		e.preventDefault();
		$.post(this.action, $(this).serialize()).always(function (xhr) {
			if (xhr.status >= 400) {
				log('error sending invite:', xhr);
			} else {
				log('sent invite');
				$inviteMessageDialog.dialog('hide');
			}
			prepInviteTab();
		});
	}).on('click', '.invite-cancel', function (e) {
		e.preventDefault();
		$inviteMessageDialog.dialog('hide');
	});

	var inviteMessageDialogTmpl = Tempo.prepare($inviteMessageDialog);
	$('.invite-filter').on('input', filterFriends);
	$('.invite-friends').on('click', '.invite-button', function () {
		var $friend = $(this).closest('.invite-friend'), fullSocialId = $friend.data('value');
		// TODO(greg): figure out why this doesn't work cross-domain
		if (/^facebook/.test(fullSocialId)) {
			window.open('about:blank', fullSocialId, 'height=640,width=1060,left=200,top=200', false);
			$('<form method=POST action=/invite target="' + fullSocialId + '" style="position:fixed;height:0;width:0;left:-99px">')
			.append('<input type=hidden name=fullSocialId value="' + fullSocialId + '">')
			.appendTo('body').submit().remove();
		} else if (/^linkedin|email/.test(fullSocialId)) {
			var name = $friend.find('.invite-name').text();
			if (/^email/.test(fullSocialId)) {
				var match = name.match(/^\s*<(.*)>$/);
				if (match) {
					name = match[1];
				}
			}
			openInviteDialog(fullSocialId, name, /^linkedin/.test(fullSocialId) );
		}
	});

	function openInviteDialog(fullSocialId, name, longForm) {
		inviteMessageDialogTmpl.render({fullSocialId: fullSocialId, label: name, longForm: longForm || false});
		$inviteMessageDialog.dialog('show');
	}

	var $unfriendDialog = $('.unfriend-dialog')
	.detach()
	.show()
	.on('click', '.dialog-cancel', function (e) {
		e.preventDefault();
		$unfriendDialog.dialog('hide');
	});
	var unfriendDialogTmpl = Tempo.prepare($unfriendDialog);

	function showUnfriendDialog(user, callback) {
		unfriendDialogTmpl.render({
			url: xhrBase + '/user/' + user.id + '/unfriend',
			name: user.name
		});
		$unfriendDialog.off('submit').on('submit', 'form', function (e) {
			e.preventDefault();
			$.post(this.action, callback);
			$unfriendDialog.dialog('hide');
		}).dialog('show');
	}

  // Friend Find

	var $foundUsers = $('.found-user-list').antiscroll({x: false, width: '100%'});
	$foundUsers.find('.antiscroll-inner').scroll(function () { // infinite scroll
		var sT = this.scrollTop, sH = this.scrollHeight;
		// tweak these values as desired
		var offset = sH / 3, toFetch = FETCH_SIZE;
		if (!$('.found-user-list-loading').is(':visible') && this.clientHeight + sT > sH - offset) {
			log('loading more users');
			prepFindTab(toFetch);
		}
	});
	var foundUsersScroller = $foundUsers.data('antiscroll');
	$(window).resize(foundUsersScroller.refresh.bind(foundUsersScroller));  // TODO: throttle, and only bind while visible
	var usersTmpl = Tempo.prepare($foundUsers).when(TempoEvent.Types.RENDER_COMPLETE, function () {
		$('.found-user-list-loading').hide();
		foundUsersScroller.refresh();
	});


	var usersTimeout;
	function filterUsers() {
		clearTimeout(usersTimeout);
		$foundUsers.find('ul').empty();
		$foundUsers.find('.no-results').hide();
		toggleFindHelp();
		usersTimeout = setTimeout(prepFindTab, 200);
	}

	$('.user-filter').on('input', filterUsers);
	$('.found-user-list').on('click', '.connect-button', function () {
		var $a = $(this),
			$user = $a.closest('.found-user'),
			o = $user.data(), xhr;
		switch (o.status) {
		case 'friend':
			showUnfriendDialog({
				id: o.id,
				name: $user.find('.user-name').text()
			}, function (data) {
				o.status = '';
				$a.closest('.found-user').data('status', o.status).removeClass('friend requested').addClass(o.status);
			});
			break;
		case 'requested':
			xhr = $.post(xhrBase + '/user/' + o.id + '/cancelRequest', function (data) {
				o.status = '';
			}).fail(function () {
				if (xhr && xhr.responseText && JSON.parse(xhr.responseText).alreadyAccepted) {
					o.status = 'friend';
				}
			});
			break;
		//case '':
		default:
			xhr = $.post(xhrBase + '/user/' + o.id + '/friend', function (data) {
				o.status = data.acceptedRequest ? 'friend' : 'requested';
			});
			break;
		}
		if (xhr) {
			xhr.always(function () {
				$a.closest('.found-user').data('status', o.status).removeClass('friend requested').addClass(o.status);
			});
		}
	});

	var usersToShow = FETCH_SIZE;
	var usersShowing = [];
	var userPageIndex = 0;
	var moreUsers = true;

	function getUserFilterInput() {
		return $.trim($('.user-filter').val() || '');
	}

	function toggleFindHelp() {
		$('.search-users-help').toggle(!getUserFilterInput());
	}

	function prepFindTab(moreToShow) {
		log('prepFindTab', moreToShow);
		if (moreToShow && !moreUsers) { return; }
		moreUsers = true;
		var search = getUserFilterInput();
		toggleFindHelp();
		if (!search) {
			moreUsers = false;
			usersTmpl.clear();
			usersShowing.length = 0;
			$foundUsers.find('.no-results').hide();
			$('.found-user-list-loading').hide();
			return;
		}
		$('.found-user-list-loading').show();
		var pageNum = moreToShow ? userPageIndex : 0,
		pageSize = usersToShow,
		opts = {
			pageNum: pageNum,
			pageSize: pageSize,
			query: search
		};
		userPageIndex = pageNum;
		//$.getJSON(xhrBase + '/user/socialConnections', opts, function (friends) {
		$.getJSON(KF.xhrBaseSearch + '/users/page', opts, function (friends) {
			if (search !== getUserFilterInput()) {
				return;
			}
			userPageIndex++;
			log('[prepFindTab] friends:', friends && friends.length, friends);
			friends.forEach(function (obj, i) {
				obj.status = obj.status || '';
				obj.image = formatPicUrl(obj.user.id, obj.user.pictureName, 200);
			});
			if (friends.length < pageSize) {
				moreUsers = false;
			}
			if (!moreToShow) {
				usersTmpl.clear();
				usersShowing.length = 0;
			}
			usersShowing.push.apply(usersShowing, friends);
			var $noResults = $foundUsers.find('.no-results').hide();
			if (!usersShowing.length) {
				$noResults.find('.no-result-filter').text(search);
				$noResults.show();
			}
			usersTmpl.append(friends);
		});
	}

	// Friend Requests

	var $friendReqs = $('.friend-reqs').antiscroll({x: false, width: '100%'});
	$friendReqs.find('.antiscroll-inner').scroll(function () {
		$friendReqs.prev().toggleClass('scrolled', this.scrollTop > 0);
	}).on('click', '.friend-req-y[href],.friend-req-n[href]', function () {
		var $a = $(this), id = $a.closest('.friend-req').data('id'), accepting = $a.hasClass('friend-req-y');
		$.post(accepting ?
				xhrBase + '/user/' + id + '/friend' :
				xhrBase + '/user/' + id + '/ignoreRequest', function () {
			$a.closest('.friend-req-act').addClass('done');
			$a.closest('.friend-req').find('.friend-req-q').text(
				accepting ? 'Accepted as your kifi friend' : 'Friend request ignored');
			updateFriendRequests(-1);
		}).fail(function () {
			$a.siblings('a').addBack().attr('href', 'javascript:');
		});
		$a.siblings('a').addBack().removeAttr('href');
	});
	var friendReqsScroller = $friendReqs.data('antiscroll');
	$(window).resize(friendReqsScroller.refresh.bind(friendReqsScroller));
	var friendReqsTmpl = Tempo.prepare($friendReqs).when(TempoEvent.Types.RENDER_COMPLETE, function () {
		$friendReqsLoading.hide();
		friendReqsScroller.refresh();
	});
	var $friendReqsLoading = $('.friend-reqs-loading');
	function prepRequestsTab() {
		$.getJSON(xhrBase + '/user/incomingFriendRequests', function (reqs) {
			log('[prepRequestsTab] req:', reqs.length);
			for (var r, i = 0; i < reqs.length; i++) {
				r = reqs[i];
				r.picUri = formatPicUrl(r.id, r.pictureName, 200);
			}
			$('.friend-reqs-status').text('You have ' + (reqs.length || 'no pending') + ' friend request' + (reqs.length === 1 ? '' : 's') + '.');
			friendReqsTmpl.render(reqs);
		});
	}

	var $blog = $('.blog-outer').antiscroll({x: false, width: '100%'});
	var blogScroller = $blog.data('antiscroll');
	$(window).resize(function () {
		if (document.body.getAttribute('data-view') === 'blog') {
			blogScroller.refresh();
		}
	});
	var blogTmpl = Tempo.prepare($blog.find('.antiscroll-inner'), {escape: false}).when(TempoEvent.Types.RENDER_COMPLETE, function () {
		$blogLoading.hide();
		blogScroller.refresh();
	});
	var $blogLoading = $('.blog-loading');
	function showBlog() {
		$('body').attr('data-view', 'blog');
		$('.left-col .active').removeClass('active');
		(window.google ? $.Deferred().resolve() : $.getScript('https://www.google.com/jsapi')).done(function () {
			google.load('feeds', '1', {callback: loadFeed});
		});
		function loadFeed() {
			var feed = new google.feeds.Feed('http://kifiupdates.tumblr.com/rss');
			feed.setNumEntries(-1);
			feed.setResultFormat(google.feeds.Feed.JSON_FORMAT);
			feed.load(function renderFeed(o) {
				log('[renderFeed]', o);
				if (o.feed) {
					var suffixes = [,'st','nd','rd'];
					o.feed.entries.forEach(function (a) {
						var s = a.publishedDate, d = +s.substr(5, 2);  // TODO: fix "31th" (or never post on 31st, ha!)
						a.shortPublishedDate = s.substr(8, 4) + d + (suffixes[d % 20] || 'th') + ', ' + s.substr(12, 4);
					});
					blogTmpl.render(o.feed);
				}
			});
		}
	}

	function doSearch(q, filter, filterChange) {
		filter = filter || 'm';

		if (q) {
			searchResponse = null;
		}
		else {
			q = searchResponse.query;
		}

		var more = !!searchResponse;
		log('[doSearch] ' + (more ? 'more ' : '') + 'q:', q, filter);
		$('.left-col .active').removeClass('active');
		$('body').attr('data-view', 'search');

		if (!searchResponse) {
			subtitleTmpl.render({
				searching: true
			});
			$checkAll.removeClass('live checked');
			$myKeeps.detach()
				.find('.keep').removeClass('selected detailed');
			$fixedTitle.empty().removeData();
		}

		$keepSpinner.show();
		$loadMore.addClass('hidden');

		$query.attr('data-q', q);
		$query.data('filter', filter);

		if (!$query.val()) {
			$query.val(q).focus()
				.closest($queryWrap).removeClass('empty');
		}

		var context = searchResponse && searchResponse.context || void 0;

		$.getJSON(KF.xhrBaseSearch, {
			q: q,
			f: filter,
			maxHits: 30,
			context: context
		}, function (data) {
			updateCollectionsIfAnyUnknown(data.hits);

			$.when(promise.me, promise.collections).done(function () {
				searchResponse = data;
				var numShown = data.hits.length + (context ? $results.find('.keep').length : 0);

				if (!(more || filterChange)) {
					myTotal = data.myTotal;
					friendsTotal = data.friendsTotal;
					othersTotal = data.othersTotal;
				}

				if (!more) {
					$mainKeeps.find('.antiscroll-inner').scrollTop(0);
				}

				subtitleTmpl.render({
					filter: filter,
					numShown: numShown,
					query: data.query,
					myTotal: myTotal || 0,
					friendsTotal: friendsTotal || 0,
					othersTotal: othersTotal || 0
				});

				if (numShown) {
					$checkAll.addClass('live');
				}

				data.hits.forEach(prepHitForRender);
				prefetchScreenshots(data.hits);

				if (context) {
					keepsTmpl.into($results[0]).append(data.hits);
				}
				else {
					keepsTmpl.into($results[0]).render(data.hits);
					//hideKeepDetails();
				}

				$checkAll.removeClass('checked');
			});
		});
	}

	function prefetchScreenshots(keeps) {
		if (!(keeps && keeps.length)) {
			return;
		}
		return $.postJson(xhrBase + '/keeps/screenshot', {
			urls: keeps.map(function (keep) {
				return keep.url;
			})
		}).done(function (data) {
			var i;
			var urls = data.urls;
			for (i in urls) {
				if (urls.hasOwnProperty(i)) {
					var url = urls[i];
					if (url) {
						prefetchedPreviewUrls[i] = url;
						setTimeout((function (url) {
							return function () {
								var img = document.createElement('img');
								img.src = url;
							};
						})(url));
					}
				}
			}
		});
	}

	function onClickSearchFilter(filter, e) {
		e.preventDefault();
		var query = $(e.target).closest('.search-filters').data('query');
		doSearch(query, filter, true);
	}

	$subtitle.on('click', '.search-filter-you', onClickSearchFilter.bind(null, 'm'));
	$subtitle.on('click', '.search-filter-friends', onClickSearchFilter.bind(null, 'f'));
	$subtitle.on('click', '.search-filter-all', onClickSearchFilter.bind(null, 'a'));

	function prepHitForRender(hit) {
		var matches = hit.bookmark.matches || {};
		$.extend(hit, hit.bookmark);

		hit.titleHtml = hit.title ?
			boldSearchTerms(hit.title, matches.title) :
			formatTitleFromUrl(hit.url, matches.url);
		hit.descHtml = formatDesc(hit.url, matches.url);
		hit.me = me;
		hit.keepers = hit.users;
		hit.others = hit.count - hit.users.length - (hit.isMyBookmark && !hit.isPrivate ? 1 : 0);
		hit.collections = hit.collections || hit.tags;
		if (hit.collections) {
			prepKeepCollections(hit.collections);
		}
		// hasExampleTag has a side effect -> sets tag.exampleClass for example tag
		hit.isExample = hasExampleTag(hit.collections);
	}

	function hasExampleTag(tags) {
		return !!tags && tags.some(function (tag) {
			if ((tag.name && tag.name.toLowerCase()) === 'example keep') {
				tag.exampleClass = 'example';
				return true;
			}
			return false;
		});
	}

	function prepKeepForRender(keep) {
		keep.titleHtml = keep.title || formatTitleFromUrl(keep.url);
		keep.descHtml = formatDesc(keep.url);
		keep.isMyBookmark = true;
		keep.me = me;
		prepKeepCollections(keep.collections);
		// hasExampleTag has a side effect -> sets tag.exampleClass for example tag
		keep.isExample = hasExampleTag(keep.collections);
	}

	var aUrlParser = document.createElement('a');
	var secLevDomainRe = /[^.\/]+(?:\.[^.\/]{1,3})?\.[^.\/]+$/;
	var fileNameRe = /[^\/]+?(?=(?:\.[a-zA-Z0-9]{1,6}|\/|)$)/;
	var fileNameToSpaceRe = /[\/._-]/g;
	function formatTitleFromUrl(url, matches) {
		aUrlParser.href = url;

		var domain = aUrlParser.hostname;
		var domainIdx = url.indexOf(domain);
		var domainMatch = domain.match(secLevDomainRe);
		if (domainMatch) {
			domainIdx += domainMatch.index;
			domain = domainMatch[0];
		}

		var fileName = aUrlParser.pathname;
		var fileNameIdx = url.indexOf(fileName, domainIdx + domain.length);
		var fileNameMatch = fileName.match(fileNameRe);
		if (fileNameMatch) {
			fileNameIdx += fileNameMatch.index;
			fileName = fileNameMatch[0];
		}
		fileName = fileName.replace(fileNameToSpaceRe, ' ').trimRight();

		for (var i = matches && matches.length; i--;) {
			var match = matches[i];
			var start = match[0], len = match[1];
			if (start >= fileNameIdx && start < fileNameIdx + fileName.length) {
				fileName = bolded(fileName, start - fileNameIdx, len);
			} else if (start >= domainIdx && start < domainIdx + domain.length) {
				domain = bolded(domain, start - domainIdx, len);
			}
		}
		fileName = fileName.trimLeft();

		return domain + (fileName ? ' · ' + fileName : '');
	}

	var strippedSchemeRe = /^https?:\/\//;
	var domainTrailingSlashRe = /^([^\/]*)\/$/;
	function formatDesc(url, matches) {
		var strippedSchemeLen = (url.match(strippedSchemeRe) || [''])[0].length;
		url = url.substr(strippedSchemeLen).replace(domainTrailingSlashRe, '$1');
		for (var i = matches && matches.length; i--;) {
			matches[i][0] -= strippedSchemeLen;
		}
		return boldSearchTerms(url, matches);
	}

	function boldSearchTerms(text, matches) {
		for (var i = matches && matches.length; i--;) {
			var match = matches[i];
			var start = match[0];
			if (start >= 0) {
				text = bolded(text, start, match[1]);
			}
		}
		return text;
	}

	function bolded(text, start, len) {
		return text.substr(0, start) + '<b>' + text.substr(start, len) + '</b>' + text.substr(start + len);
	}

	function prepKeepCollections(colls) {
		for (var i = 0; i < colls.length; i++) {
			colls[i] = collIdAndName(colls[i]);
		}
	}

	function addNewKeeps() {
		if (!$myKeeps[0].parentNode) { return; }  // in search
		var params = {}, keepId = $myKeeps.find('.keep').first().data('id');
		if (keepId) {
			params.after = keepId;
		} else if (!promise.keeps || promise.keeps.state() === 'pending') {
			log('[anyNewKeeps] keeps not loaded yet');
			return;
		}

		if ($('.left-col h3.active').is('.collection')) {
			params.collection = $('.left-col h3.active').data('id');
		}
		log('[anyNewKeeps] fetching', params);
		$.getJSON(xhrBase + '/keeps/all', params, function (data) {
			updateCollectionsIfAnyUnknown(data.keeps);
			$.when(promise.collections).done(function () {
				var keepIds = $myKeeps.find('.keep').map(getDataId).get().reduce(function (ids, id) {ids[id] = true; return ids; }, {});
				var keeps = data.keeps.filter(function (k) {return !keepIds[k.id]; });
				keeps.forEach(prepKeepForRender);
				prefetchScreenshots(keeps);
				keepsTmpl.into($myKeeps[0]).prepend(keeps);
				// TODO: insert this group heading if not already there
				$myKeeps.find('.keep-group-title.today').prependTo($myKeeps);
			});
		});
	}

	function showMyKeeps(collId) {
		collId = collId || null;
		var $h3 = $('.left-col h3');
		$h3.filter('.active').removeClass('active');
		var $active = $h3.filter(collId ? "[data-id='" + collId + "']" : '.my-keeps');

		if (collId && !$active.length) {
			clearTagInput();
			$active = $('.left-col h3').filter("[data-id='" + collId + "']");
		}

		$active.addClass('active');

		if (collId) {
			scrolledIntoViewLazy($active[0]);
		}

		var fromSearch = $('body').attr('data-view') === 'search';
		$('body').attr('data-view', 'mine');

		if (collId) {
			$mainHead.find('h1').text('Tags / ' + collections[collId].name).addClass('tag-head');
		}
		else {
			$mainHead.find('h1').text('Browse your Keeps').removeClass('tag-head');
		}

		$results.empty();
		$query.val('').removeAttr('data-q');
		$queryWrap.addClass('empty');
		$fixedTitle.empty().removeData();
		if (fromSearch) {
			searchResponse = null;
			$checkAll.removeClass('checked');
		}
		if (!$myKeeps[0].parentNode) {
			$myKeeps.insertAfter($results);
		}

		if ($myKeeps.data('collId') !== collId || !('collId' in $myKeeps.data())) {
			$myKeeps.data('collId', collId).empty();
			lastKeep = null;
			//hideKeepDetails();
			loadKeeps(collId);
		} else {
			var numShown = $myKeeps.find('.keep').length;
			subtitleTmpl.render({
				numShown: numShown,
				numTotal: collId ? collections[collId].keeps : myKeepsCount,
				collId: collId || undefined
			});
			$checkAll.toggleClass('live', numShown > 0).removeClass('checked');
			addNewKeeps();
		}
	}

	function loadKeeps(collId) {
		if (lastKeep !== 'end') {
			$keepSpinner.show();
			$loadMore.addClass('hidden');
			if (!lastKeep) {
				subtitleTmpl.render({});
				$checkAll.removeClass('live checked');
			}
			var params = {count: 30};
			if (collId) {
				params.collection = collId;
			}
			if (lastKeep) {
				params.before = lastKeep;
			}
			log('Fetching %d keeps %s', params.count, lastKeep ? 'before ' + lastKeep : '');
			promise.keeps = $.getJSON(xhrBase + '/keeps/all', params, function withKeeps(data) {
				updateCollectionsIfAnyUnknown(data.keeps);
				$.when(promise.me, promise.collections).done(function () {
					var numShown = $myKeeps.find('.keep').length + data.keeps.length;
					subtitleTmpl.render({
						numShown: numShown,
						numTotal: collId ? collections[collId].keeps : myKeepsCount,
						collId: collId || undefined
					});
					$checkAll.toggleClass('live', numShown > 0);
					$keepSpinner.hide();
					if (!data.keeps.length) {  // no more
						lastKeep = 'end';
					} else {
						data.keeps.forEach(prepKeepForRender);
						prefetchScreenshots(data.keeps);
						if (lastKeep == null) {
							keepsTmpl.into($myKeeps[0]).render(data.keeps);
						} else {
							keepsTmpl.into($myKeeps[0]).append(data.keeps);
						}
						lastKeep = data.keeps[data.keeps.length - 1].id;
						$checkAll.removeClass('checked');
					}
				});
			});
		}
	}

	function filterTags(tags, text) {
		return window.scorefilter.filter(text, tags, {
			pre: '<b>',
			post: '</b>',
			extract: function (tag) {
				return tag.name;
			}
		}).map(function (res) {
			return res.original;
		});
	}

	function highlightPrevTag() {
		highlightTag(getPrevTag());
	}

	function highlightNextTag() {
		highlightTag(getNextTag());
	}

	function getFirstTag() {
		return $collList.find('.collection:first');
	}

	function getLastTag() {
		return $collList.find('.collection:last');
	}

	function getPrevTag() {
		var $prev = getHighlightedTag();
		if ($prev.length) {
			var $next = $prev.prev('.collection');
			if ($next.length) {
				return $next;
			}
		}
		return getLastTag();
	}

	function getNextTag() {
		var $prev = getHighlightedTag();
		if ($prev.length) {
			var $next = $prev.next('.collection');
			if ($next.length) {
				return $next;
			}
		}
		return getFirstTag();
	}

	function selectTag() {
		var $highlight = getHighlightedTag();
		if ($highlight.length) {
			if ($highlight.data('id')) {
				$highlight.find('a').click();
			}
			else {
				createTag($highlight.data('name'));
			}
		}
		else {
			createTag(getTagInputValue());
		}
	}

	function clearTagInput() {
		if ($.trim($newTagInput.val())) {
			$newTagInput.val('');
			updateTags();
		}
	}

	function createTag(name) {
		if (name) {
			clearTagInput();

			createCollection(name, function (collId) {
				$newColl.removeClass('submitted');
			});
		}
	}

	function highlightTag($el) {
		getHighlightedTag().removeClass('highlight');
		if ($el && $el.length) {
			scrolledIntoViewLazy($el[0]);
			return $el.addClass('highlight');
		}
	}

	function scrolledIntoViewLazy(el, padding) {
		var view;
		if (!(el && (view = el.offsetParent))) {
			return;
		}

		var viewTop = view.scrollTop,
		viewHeight = view.clientHeight,
		viewBottom = viewTop + viewHeight,
		elemTop = el.offsetTop,
		elemBottom = elemTop + el.offsetHeight;

		if (elemBottom > viewBottom) {
			view.scrollTop = elemBottom + (padding || 0) - viewHeight;
		}
		else if (elemTop < viewTop) {
			view.scrollTop = elemTop - (padding || 0);
		}
	}

	function getHighlightedTag() {
		return $collList.find('.collection.highlight');
	}

	function getTagInputValue() {
		return $.trim($newTagInput && $newTagInput.val() || '');
	}

	function getTagById(id) {
		return $collList.find('.collection[data-id=' + id + ']');
	}

	function normalizeTagName(name) {
		return name ? $.trim(name).replace(/\s+/g, ' ').toLowerCase() : '';
	}

	function getTagByName(name) {
		name = normalizeTagName(name);
		return $collList.find('.collection').filter(function () {
			return name && normalizeTagName($(this).data('name')) === name;
		});
	}

	var newTagTemplate = Handlebars.compile($('#tag-new-template').html());

	function updateTags(tags) {
		if (!collections) {
			return;
		}
		if (!tags) {
			tags = util.values(collections);
		}

		var val = getTagInputValue(),
		highlightId = getHighlightedTag().data('id');

		if (val) {
			tags = filterTags(tags, val);
		}
		$newColl.toggleClass('non-empty', Boolean(val));

		var $inner = $collList.find('.antiscroll-inner');
		$inner.empty();
		collTmpl.render(tags);

		if (val) {
			var matched = getTagByName(val);
			if (!matched.length) {
				var newTagHtml = newTagTemplate({
					name: val
				});
				$inner.append(newTagHtml);
			}

			var highlighted = false;
			if (!(highlightId && highlightTag(getTagById(highlightId)))) {
				highlightTag(getFirstTag());
			}
		}
	}

	function updateCollections() {
		promise.collections = $.getJSON(xhrBase + '/collections/all?sort=user&_=' + Date.now().toString(36), function (data) {
			collections = data.collections.reduce(function (o, c) {o[c.id] = c; return o; }, {});
			if ($collList.find('.renaming, .showing, .sortable-placeholder').length === 0) {
				updateTags(data.collections);
			}
			$('.left-col .my-keeps .nav-count').text(myKeepsCount = data.keeps);
		}).promise();
	}

	function updateCollectionsIfAnyUnknown(keeps) {
		if (collections && keeps.some(function (k) {return k.collections && k.collections.some(function (id) {return !collections[id]; }); })) {
			updateCollections();
		}
	}

	function showMessage(msg) {
		$.fancybox($('<p>').text(msg));
	}

	function getDataId() {
		return $(this).data('id');
	}

	var $collMenu = $('#coll-menu')
	.on('mouseover', 'a', function () {
		$(this).addClass('hover');
	}).on('mouseout', 'a', function () {
		$(this).removeClass('hover');
	}).on('mouseup mousedown', '.coll-remove', function (e) {
		if (e.which > 1 || !$collMenu.hasClass('showing')) { return; }
		hideCollMenu();
		var $coll = $collMenu.closest('.collection');
		var collId = $coll.data('id');
		log('Removing tag', collId);
		$.postJson(xhrBase + '/collections/' + collId + '/delete', {}, function (data) {
			delete collections[collId];
			$coll.slideUp(80, $.fn.remove.bind($coll));
			if ($myKeeps.data('collId') === collId) {
				$myKeeps.removeData('collId');
				showMyKeeps();
			}
			var $keepColl = $main.find('.keep-coll[data-id=' + collId + ']');
			if ($keepColl.length) { $keepColl.css('width', $keepColl[0].offsetWidth); }
			var $pageColl = $detail.find('.page-coll[data-id=' + collId + ']');
			if ($pageColl.length) { $pageColl.css('width', $pageColl[0].offsetWidth); }
			$keepColl.add($pageColl).layout().on('transitionend', removeIfThis).addClass('removed');
		}).fail(showMessage.bind(null, 'Could not delete tag, please try again later'));
	}).on('mouseup mousedown', '.coll-rename', function (e) {
		if (e.which > 1 || !$collMenu.hasClass('showing')) { return; }
		hideCollMenu();
		var $coll = $collMenu.closest('.collection').addClass('renaming').each(function () {
			var scrEl = $collList.find('.antiscroll-inner')[0], oT = this.offsetTop;
			if (scrEl.scrollTop > oT) {
				scrEl.scrollTop = oT;
			}
		});
		var $name = $coll.find('.view-name'), name = $name.text();
		var $in = $("<input type=text placeholder='Type new tag name'>").val(name).data('orig', name);
		$name.empty().append($in);
		setTimeout(function () {
			$in[0].setSelectionRange(0, name.length, 'backward');
			$in[0].focus();
		});
		$in.on('blur keydown', function (e) {
			if (e.which === 13 || e.type === 'blur') { // 13 is Enter
				var oldName = $in.data('orig');
				var newName = $.trim(this.value) || oldName;
				if (newName !== oldName) {
					var collId = $coll.addClass('renamed').data('id');
					$.postJson(xhrBase + '/collections/' + collId + '/update', {name: newName}, function () {
						collections[collId].name = newName;
						if ($myKeeps.data('collId') === collId) {
							$main.find('h1').text(newName);
						}
					}).fail(function (jqXhr) {
						try {
							var errorReason = JSON.parse(jqXhr.responseText).error;
							showMessage('Could not rename: ' + errorReason);
						} catch (e) {
							showMessage('Could not rename tag, please try again later');
						}
						$name.text(oldName);
					});
				}
				exitRename(newName);
			} else if (e.which === 27) {
				exitRename();
			}
		});
		function exitRename(name) {
			name = name || $in.data('orig');
			$in.remove();
			$name.text(name);
			$coll.on('transitionend', function end(e) {
				if (e.target === this) {
					$coll.off('transitionend', end).removeClass('renamed');
				}
			}).addClass('renamed').removeClass('renaming');
		}
	});
	function hideCollMenu() {
		log('[hideCollMenu]');
		document.removeEventListener('mousedown', $collMenu.data('docMouseDown'), true);
		$collMenu.removeData('docMouseDown').one('transitionend', function () {
			$collMenu.detach().find('.hover').removeClass('hover');
		}).removeClass('showing')
		.closest('.collection').removeClass('with-menu');
	}

	$(document).keydown(function (e) {  // auto focus on search field when starting to type anywhere on the document
		if (!$(e.target).is('input,textarea') && e.which >= 48 && e.which <= 90 && !e.ctrlKey && !e.metaKey && !e.altKey) {
			$query.focus();
		}
	}).on('click', 'a[href]', function (e) {
		var href;
		if (!e.isDefaultPrevented() && ~(href = this.getAttribute('href')).search(inPageNavRe)) {
			e.preventDefault();
			navigate(href);
		}
	});
	var inPageNavRe = /^(?:$|[a-z0-9]+(?:$|\/))/i;

	var baseUriRe = new RegExp('^' + ($('base').attr('href') || ''));
	History.setTitle(History.getState());

	$(window).on('statechange anchorchange', function (e) {
		hideUndo();
		showNotification(getUriParam('m'));
		checkEmailVerified();
		var state = History.getState();
		var hash = state.hash.replace(baseUriRe, '').replace(/^\.\//, '').replace(/[?#].*/, '');
		var parts = hash.split('/');
		log('[' + e.type + ']', hash, state);
		switch (parts[0]) {
		case '':
			navigate('');
			showMyKeeps();
			break;
		case 'tag':
			$.when(promise.collections).done(function () {
				var collId = parts[1];
				if (collections[collId]) {
					if (collId !== $collList.find('.collection.active').data('id')) {
						showMyKeeps(collId);
					}
				} else {
					showMessage('Sorry, unable to view this tag.');
					e.preventDefault();
				}
			});
			break;
		case 'find':
			doSearch(decodeURIComponent(queryFromUri(state.hash)));
			break;
		case 'profile':
			showProfile();
			break;
		case 'friends':
			promise.me.done(function () {
				showFriends(hash);
			});
			break;
		case 'blog':
			showBlog();
			break;
		default:
			return;
		}
		//hideKeepDetails();
		kifiTracker.view('/' + hash);
	});

	function navigate(uri, opts) {
		var baseUri = document.baseURI;
		if (uri.substr(0, baseUri.length) === baseUri) {
			uri = uri.substr(baseUri.length);
		}
		var title, kind = uri.match(/[\w-]*/)[0];
		log('[navigate]', uri, opts || '', kind);
		var clearTags = true;
		switch (kind) {
		case '':
			title = 'Your Keeps';
			break;
		case 'collection':
		case 'tag':
			title = collections[uri.substr(kind.length + 1)].name;
			clearTags = false;
			break;
		case 'find':
			title = queryFromUri(uri);
			break;
		case 'profile':
			title = 'Profile';
			break;
		case 'friends':
			title = {
				friends: 'Friends',
				'friends/invite': 'Invite Friends',
				'friends/find': 'Find Friends',
				'friends/requests': 'Friend Requests'
			}[uri];
			break;
		case 'blog':
			title = 'Updates and Features';
			break;
		}
		if (clearTags) {
			clearTagInput();
		}
		if (!title || title == '') {
			title = "Kifi";
		} else {
			title = 'Kifi • ' + title;
		}
		History[opts && opts.replace ? 'replaceState' : 'pushState'](null, title, uri);
	}

	function queryFromUri(uri) {
		return uri.replace(/.*?[?&]q=([^&]*).*/, '$1').replace(/\+/g, ' ');
	}

	var $main = $('.main').on('mousedown', '.keep-checkbox', function (e) {
		e.preventDefault();  // avoid starting selection
	}).on('click', '.keep-title>a', function (e) {
		e.stopPropagation();
	}).on('click', '.keep', function (e) {
		var $keep = $(this), $keeps = $main.find('.keep'), $el = $(e.target);
		if ($el.hasClass('keep-checkbox') || $el.hasClass('handle')) {
			$keep.toggleClass('selected');
			var $selected = $keeps.filter('.selected');
			$checkAll.toggleClass('checked', $selected.length === $keeps.length);
			updateSubtitleTextForSelection($selected.length);
			if ($selected.length === 0 ||
				$selected.not('.detailed').addClass('detailed').length +
				$keeps.filter('.detailed:not(.selected)').removeClass('detailed').length === 0) {
				return;  // avoid redrawing same details
			}
		} else if ($el.hasClass('pic') || $el.hasClass('keep-coll-a')) {
			return;
		} else if ($keep.hasClass('selected')) {
			$keeps.filter('.selected').toggleClass('detailed');
			$keeps.not('.selected').removeClass('detailed');
		} else if ($keep.hasClass('detailed')) {
			$keep.removeClass('detailed');
			$keeps.filter('.selected').addClass('detailed');
		} else {
			$keeps.removeClass('detailed');
			$keep.addClass('detailed');
		}
		updateKeepDetails();
	});
	var $mainHead = $('.main-head');
	var $mainKeeps = $('.main-keeps').antiscroll({x: false, width: '100%'});
	$mainKeeps.find('.antiscroll-inner').scroll(function () { // infinite scroll
		var sT = this.scrollTop;
		if ($keepSpinner.css('display') === 'none' && this.clientHeight + sT > this.scrollHeight - 300) {
			if ($main[0].querySelector('.keep.selected')) {
				$loadMore.removeClass('hidden');
			} else {
				$loadMore.triggerHandler('click');
			}
		}
		if ($fixedTitle[0].hasChildNodes()) {
			var o = $fixedTitle.data(), curr = o.$titles[o.i], next = o.$titles[o.i + 1], h = $fixedTitle[0].offsetHeight, d;
			if (next && (d = sT + h - next.offsetTop) > 0) {
				if (d >= h) {
					$fixedTitle.text(o.$titles.eq(++o.i).text()).css('top', 0);
				} else {
					$fixedTitle.css('top', -d);
				}
			} else if (o.i && (d = curr.offsetTop - sT) > 0) {
				$fixedTitle.text(o.$titles.eq(--o.i).text()).css('top', Math.min(0, d - h));
			} else if (parseInt($fixedTitle.css('top'), 10)) {
				$fixedTitle.css('top', 0);
			}
			$mainKeeps.removeClass('scrolled');
		} else {
			$mainKeeps.toggleClass('scrolled', sT > 0);
		}
	});
	var mainScroller = $mainKeeps.data('antiscroll');
	$(window).resize(mainScroller.refresh.bind(mainScroller));

	var $splash = $('.splash');
	var splashScroller = $splash.antiscroll({x: false, width: '100%'}).data('antiscroll');
	$(window).resize(splashScroller.refresh.bind(splashScroller));

	var detailScroller = $detail.antiscroll({x: false, width: '100%'}).data('antiscroll');
	$(window).resize(detailScroller.refresh.bind(detailScroller));

	$splash.on('click', '.kifi-tutorial', function (e) {
		e.preventDefault();
		initOnboarding(true);
	});

	var $queryWrap = $('.query-wrap');
	$queryWrap.focusin($.fn.addClass.bind($queryWrap, 'focus'));
	$queryWrap.focusout($.fn.removeClass.bind($queryWrap, 'focus'));
	var $query = $('input.query').on('keydown input', function (e) {
		log('[clearTimeout]', e.type);
		clearTagInput();
		clearTimeout(searchTimeout);
		var val = this.value, q = $.trim(val);
		$queryWrap.toggleClass('empty', !val);
		if (q === ($query.attr('data-q') || '')) {
			log('[query:' + e.type + '] no change');
		} else if (!q) {
			navigate('');
		} else if (!e.which || e.which === 13) { // Enter
			var uri = 'find?q=' + encodeURIComponent(q).replace(/%20/g, '+');
			if (e.which) {
				navigate(uri);
			} else {
				searchTimeout = setTimeout(navigate.bind(null, uri), 500);  // instant search
			}
		}
	});
	$('.query-mag').mousedown(function (e) {
		if (e.which === 1) {
			e.preventDefault();
			$query.focus();
		}
	});
	$('.query-x').click(function () {
		$query.val('').focus().triggerHandler('input');
	});

	var $collList = $('#collections-list')
	.each(function () {this.style.top = this.offsetTop + 'px'; })
	.addClass('positioned')
	.antiscroll({x: false, width: '100%', autoHide: false})
	.sortable({
		axis: 'y',
		items: '.collection',
		cancel: '.coll-tri,#coll-menu,.renaming',
		opacity: 0.6,
		placeholder: 'sortable-placeholder',
		beforeStop: function (event, ui) {
			// update the collection order
			$.postJson(xhrBase + '/collections/ordering', $(this).find('.collection').map(getDataId).get(), function (data) {
				log(data);
			}).fail(function () {
				showMessage('Could not reorder the tags, please try again later');
				// TODO: revert the re-order in the DOM
			});
		},
		stop: function (e, ui) {
			ui.item.css({
				position: '',
				opacity: '',
				zIndex: ''
			});
		}
	}).on('mousedown', '.coll-tri', function (e) {
		if (e.button > 0) { return; }
		e.preventDefault();  // do not start selection
		var $tri = $(this), $coll = $tri.closest('.collection');
		$coll.addClass('with-menu');
		$collMenu.hide().removeClass('showing').appendTo($coll)
			.toggleClass('page-bottom', $coll[0].getBoundingClientRect().bottom > $(window).height() - 70)
			.show().layout().addClass('showing')
			.data('docMouseDown', docMouseDown);
		document.addEventListener('mousedown', docMouseDown, true);
		function docMouseDown(e) {
			if (!e.button && !$.contains($collMenu[0], e.target)) {
				hideCollMenu();
				if ($(e.target).hasClass('coll-tri')) {
					e.stopPropagation();
				}
			}
		}
	});
	var collScroller = $collList.data('antiscroll');
	$(window).resize(collScroller.refresh.bind(collScroller));

	$collList.on('click', '.collection', function (e) {
		var $el = $(this);
		if (!$el.data('id')) {
			e.preventDefault();
			createTag($el.data('name'));
		}
	});

	$colls.on('click', 'h3.collection>a', function (e) {
		if (e.target === this && $(this.parentNode).hasClass('renaming')) {
			e.preventDefault();
			$(this).find('input').focus();
		}
	});

	var $newColl = $colls.find('.collection-new'),
	$newTagInput = $newColl.find('input');
	$newTagInput.on('keydown', function (e) {
		switch (e.which) {
		case 13: // Enter
			selectTag();
			break;
		case 38: // up
			highlightPrevTag();
			break;
		case 40: // down
			highlightNextTag();
			break;
		}
	})
	.on('input', function (e) {
		updateTags();
	});

	$newColl.on('click', '.tag-input-clear', function (e) {
		e.preventDefault();
		e.stopPropagation();
		e.stopImmediatePropagation();
		clearTagInput();
	});

	function createCollection(name, callback) {
		$newColl.addClass('submitted');
		$.postJson(xhrBase + '/collections/create', {
			name: name
		}, function (data) {
			collTmpl.prepend(collections[data.id] = {
				id: data.id,
				name: name,
				keeps: 0
			});
			$collList.find('.antiscroll-inner')[0].scrollTop = 0;
			callback(data.id);
		}).fail(function () {
			showMessage('Could not create tag, please try again later');
			callback();
		});
	}

	function getTagCount(tagId) {
		return collections[tagId].keeps;
	}

	function setTagCount(tagId, val) {
		return collections[tagId].keeps = val;
	}

	function updateTagCountAndDOM(tagId, val) {
		val = val || 0;
		setTagCount(tagId, val);
		$collList.find('.collection[data-id=' + tagId + '] .tag-count').text(val);
		return val;
	}

	function addTagCountAndDOM(tagId, val) {
		val = getTagCount(tagId) + (val || 0);
		return updateTagCountAndDOM(tagId, val);
	}

	function addKeepsToCollection(collId, $keeps) {
		return $.postJson(xhrBase + '/keeps/add', {
			collectionId: collId,
			keeps: $keeps.map(function () {
				var a = this.querySelector('.keep-title>a');
				var isPrivate = this.querySelector('.keep-private.on') != null;
				return {
					title: a.title,
					url: a.href,
					isPrivate: isPrivate
				};
			}).get()
		})
		.done(function (data) {
			addTagCountAndDOM(collId, data.addedToCollection);

			var collName = collections[collId].name;

			$keeps
			.addClass('mine')
				.find('.keep-colls:not(:has(.keep-coll[data-id=' + collId + ']))')
					.contents()
						.filter(function () {return this.nodeType === 3; })
						.remove()
						.end()
					.end()
				.append(
					'<span class=keep-coll data-id=' + collId + '>' +
					'<a class="keep-coll-a" href="javascript:">' + collName +'</a>' +
					'<a class="keep-coll-x" href="javascript:"></a>' +
					'</span>'
				);

			if ($keeps.is('.detailed')) {
				$detail
					.children()
					.attr('data-kept', $keeps.has('.keep-private.on').length === $keeps.length ? 'pri' : 'pub');

				var $inColl = $detail.find('.page-coll-list');
				if (!$inColl.has('.page-coll[data-id=' + collId + ']').length) {
					inCollTmpl.into($inColl[0]).append({
						id: collId,
						name: collName
					});
				}
			}
		})
		.fail(function () {
			showMessage('Could not add to tag, please try again later');
		});
	}

	function removeFromCollection(collId, $keeps) {
		$.postJson(xhrBase + '/collections/' + collId + '/removeKeeps', $keeps.map(getDataId).get(), function (data) {
			if ($collList.find('.collection.active').data('id') === collId) {
				var $titles = obliviate($keeps);
				//hideKeepDetails();
				showUndo(
					($keeps.length > 1 ? $keeps.length + ' Keeps' : 'Keep') + ' removed from this tag.',
					undoRemoveFromCollection.bind(null, collId, $keeps, $titles),
					$.fn.remove.bind($keeps));
			} else {
				var $keepColl = $keeps.find('.keep-coll[data-id=' + collId + ']');
				$keepColl.css('width', $keepColl[0].offsetWidth).layout().on('transitionend', removeIfThis).addClass('removed');
				var $pageColl = $detail.find('.page-coll[data-id=' + collId + ']');
				$pageColl.css('width', $pageColl[0].offsetWidth).layout().on('transitionend', removeIfThis).addClass('removed');
			}

			addTagCountAndDOM(collId, -data.removed);
		}).fail(showMessage.bind(null, 'Could not remove keep' + ($keeps.length > 1 ? 's' : '') + ' from tag, please try again later'));
	}

	function undoRemoveFromCollection(collId, $keeps, $titles) {
		$.postJson(xhrBase + '/collections/' + collId + '/addKeeps', $keeps.map(getDataId).get(), function (data) {
			rennervate($keeps, $titles);
			updateKeepDetails();
			addTagCountAndDOM(collId, data.added);
		});
	}

	function removeIfThis(e) {
		if (e.target === this) {
			$(this).remove();
		}
	}

	function detachAndOff(e) {
		if (e.target === this) {
			$(this).off(e.type, detachAndOff).detach();
		}
	}

	var hideAddCollTimeout;

	// closes the detail pane when user clicks outside of the pane/keep
	$(document).on('click', function (e) {
		var $target = $(e.target);
		if (!(e.isDefaultPrevented() || $target.closest('.detail,.keep,:focusable').length)) {
			if ($main.find('.keep.detailed:not(.selected)').removeClass('detailed').length) {
				updateKeepDetails();
			}
		}
	});
	$detail.on('click', '.page-x', function () {
		$main.find('.keep.detailed').removeClass('detailed');
		//hideKeepDetails();
	})
	.on('click', '.page-keep,.page-priv', function (e) {
		var $keeps = $main.find('.keep.detailed');
		var $a = $(this), howKept = $detail.children().attr('data-kept');
		if (!howKept) {  // keep
			howKept = $a.hasClass('page-keep') ? 'pub' : 'pri';
			$.postJson(xhrBase + '/keeps/add', {
					keeps: $keeps.map(function () {
						var a = $(this).find('.keep-title>a')[0];
						return {title: a.title, url: a.href, isPrivate: howKept === 'pri'};
					}).get()
				}, function (data) {
					$detail.children().attr('data-kept', howKept).find('.page-how').attr('class', 'page-how ' + howKept);
					$keeps.addClass('mine').find('.keep-private').toggleClass('on', howKept === 'pri');
				}).fail(showMessage.bind(null, 'Could not add Keeps, please try again later'));
		} else if ($a.hasClass('page-keep')) {  // unkeep
			$.postJson(xhrBase + '/keeps/remove', $keeps.map(function () {return {url: this.querySelector('.keep-title>a').href}; }).get(), function (data) {
				// TODO: update number in "Showing top 30 results" tagline? load more instantly if number gets too small?
				var collCounts = $keeps.find('.keep-coll').map(getDataId).get()
					.reduce(function (o, id) {o[id] = (o[id] || 0) + 1; return o; }, {});
				for (var collId in collCounts) {
					addTagCountAndDOM(collId, -collCounts[collId]);
				}
				var $keepsStaying = searchResponse ? $keeps.has('.keep-friends,.keep-others') : $();
				$keepsStaying.each(function () {
					var $keep = $(this), $priv = $keep.find('.keep-private');
					$keep.data({
						sel: $keep.hasClass('selected'),
						priv: $priv.hasClass('on'),
						$coll: $keep.find('.keep-coll')
					})
					.removeClass('mine selected detailed');
					$priv.removeClass('on');
				});
				var $keepsGoing = $keeps.not($keepsStaying);
				var $titles = obliviate($keepsGoing);
				//hideKeepDetails();
				showUndo(
					$keeps.length > 1 ? $keeps.length + ' Keeps deleted.' : 'Keep deleted.',
					undoUnkeep.bind(null, $keeps, $titles),
					$.fn.remove.bind($keepsGoing));
			}).fail(showMessage.bind(null, 'Could not delete keeps, please try again later'));
		} else {  // toggle public/private
			howKept = howKept === 'pub' ? 'pri' : 'pub';
			$detail.children().attr('data-kept', howKept).find('.page-how').attr('class', 'page-how ' + howKept);
			$keeps.each(function () {
				var $keep = $(this), keepLink = $keep.find('.keep-title>a')[0];
				// TODO: support bulk operation with one server request
				$.postJson(
					xhrBase + '/keeps/add',
					{keeps: [{title: keepLink.title, url: keepLink.href, isPrivate: howKept === 'pri'}]},
					function () {
						$keep.find('.keep-private').toggleClass('on', howKept === 'pri');
					}).fail(showMessage.bind(null, 'Could not update keep, please try again later'));
			});
		}
	}).on('click', '.page-coll-x', function (e) {
		e.preventDefault();
		removeFromCollection($(this.parentNode).data('id'), $main.find('.keep.detailed'));
	}).on('click', '.page-coll-add', function () {
		var $btn = $(this),
			$in = $('.page-coll-input').css('width', $btn.outerWidth());

		$btn.hide();
		$in.add('.page-coll-sizer').show();
		$in.layout()
			.css('width', '')
			.prop('disabled', false)
			.focus()
			.select()
			.trigger('input');
	}).on('blur', '.page-coll-input', function (e) {
		var input = this;
		hideAddCollTimeout = setTimeout(hide, 50);

		function hide() {
			clearTimeout(hideAddCollTimeout);
			hideAddCollTimeout = null;

			var $btn = $('.page-coll-add')
				.css({
					display: '',
					visibility: 'hidden',
					position: 'absolute'
				}),
				width = $btn.outerWidth();

			$btn.css({
				display: 'none',
				visibility: '',
				position: ''
			});

			var $in = $(input)
				.val('')
				.layout()
				.css('width', width)
				.prop('disabled', true)
				.add('.page-coll-sizer')
				.hide()
				.css('width', '');
			$btn.show();

			$('.page-coll-opts').empty().hide();
		}
	}).on('focus', '.page-coll-input', function (e) {
		clearTimeout(hideAddCollTimeout);
		hideAddCollTimeout = null;
	}).on('keydown', '.page-coll-input', function (e) {
		switch (e.which) {
		case 13: // Enter
			var $opt = $('.page-coll-opt.current');
			if ($opt.length) {
				$opt.mousedown();
			} else {
				this.blur();
			}
			break;
		case 27: // Esc
			this.blur();
			break;
		case 38: // Up
		case 40: // Down
			e.preventDefault();
			var $old = $('.page-coll-opt.current'),
				$new = $old[e.which === 38 ? 'prev' : 'next']('.page-coll-opt');
			if ($new.length) {
				$old.removeClass('current');
				$new.addClass('current');
			}
			break;
		}
	}).on('input', '.page-coll-input', function (e) {
		var width = $(this.previousElementSibling).text(this.value).outerWidth();
		$(this).css('width', Math.min(Math.max(100, width) + 34, $('.page-colls').outerWidth()));
		var allColls = $.map(collections, identity), colls;
		var val = $.trim(this.value);
		if (val) {
			var re = val.split(/\s+/).map(function (p) {return new RegExp('\\b' + p, 'i'); });
			var scores = {};
			colls = allColls.filter(function (c) {
				var arr = re.map(function (re) {return re.exec(c.name); });
				if (arr.every(identity)) {
					scores[c.id] = arr.reduce(function (score, m) {
						score.min = Math.min(score.min, m.index);
						score.sum += m.index;
						return score;
					}, {min: Infinity, sum: 0});
					return true;
				}
			}).sort(function (c1, c2) {
				var s1 = scores[c1.id];
				var s2 = scores[c2.id];
				return (s1.min - s2.min) || (s1.sum - s2.sum) || c1.name.localeCompare(c2.name, undefined, compareSort);
			}).splice(0, 4).map(function (c) {
				for (var name = escapeHTMLContent(c.name), i = re.length; i--;) {
					name = name.replace(new RegExp('^((?:[^&<]|&[^;]*;|<[^>]*>)*)\\b(' + re[i].source + ')', 'gi'), '$1<b>$2</b>');
				}
				return {id: c.id, name: name};
			});
			if (!allColls.some(function (c) {return c.name.localeCompare(val, undefined, compareSearch) === 0; })) {
				colls.push({id: '', name: val});
			}
		} else {
			colls = allColls.sort(function (c1, c2) {
				return c2.keeps - c1.keeps || c1.name.localeCompare(c2.name, undefined, compareSort);
			}).splice(0, 4).map(function (c) {
				return {id: c.id, name: escapeHTMLContent(c.name)};
			});
		}
		var $opts = $('.page-coll-opts').hide();
		collOptsTmpl.into($opts).render(colls);
		if (e.isTrigger) {
			$opts.slideDown(120);
		} else {
			$opts.show();
		}
		$('.page-coll-opt:first-child').addClass('current');
	}).on('mousemove', '.page-coll-opt', function () {
		if (this.className.indexOf('current') < 0) {
			$(this).siblings('.current').removeClass('current').end().addClass('current');
		}
	}).on('mousedown', '.page-coll-opt', function (e) {
		e.preventDefault();  // selection start

		if (e.which > 1) {
			return;
		}

		var collId = $(this).data('id'),
			$in = $('.page-coll-input');

		if (collId) {
			withCollId(collId);
		}
		else {
			createCollection($.trim($in.val()), withCollId);
		}

		function withCollId(collId) {
			if (collId) {
				$in.val('').trigger('input');
				addKeepsToCollection(collId, $main.find('.keep.detailed'));
			}
		}
	});

	// Removes keeps from current view using animation, detaches them from DOM, and remembers their positions.
	// Also removes (and returns) any keep group titles that no longer have any keeps beneath them.
	function obliviate($keeps) {
		$keeps.each(function () {
			$(this).each(notePosition).css('height', this.offsetHeight);
		}).layout().addClass('toggling').on('transitionend', detachAndOff).addClass('unkept');
		return searchResponse ? $() :
			$myKeeps.find('.keep-group-title').filter(function () {
				for (var $li = $(this); ($li = $li.next()).hasClass('keep');) {
					if (!$li.hasClass('detailed')) { return; }
				}
				return true;
			}).each(notePosition).on('transitionend', detachAndOff).css('height', 0);

		function notePosition() {
			$(this).data({par: this.parentNode, prev: this.previousElementSibling});
		}
	}

	// Uses animation to bring back keeps and titles removed using obliviate.
	function rennervate($keeps, $titles) {
		$titles.each(reattach).layout().css('height', '');
		$keeps.each(reattach).layout().one('transitionend', function () {
			$(this).removeClass('toggling').css('height', '');
		}).removeClass('unkept');

		function reattach() {
			var $t = $(this), data = $t.data();
			if (data.prev) {
				$t.insertAfter(data.prev);
			} else {
				$t.prependTo(data.par);
			}
			$t.removeData('par prev');
		}
	}

	function undoUnkeep($keeps, $titles) {
		$.postJson(xhrBase + '/keeps/add', {
				keeps: $keeps.map(function () {
					var a = this.querySelector('.keep-title>a'), data = $(this).data();
					return {title: a.title, url: a.href, isPrivate: 'priv' in data ? data.priv : !!this.querySelector('.keep-private.on')};
				}).get()
			}, function (data) {
				var $keepsRemoved = $keeps.filter(function () {return 'prev' in $(this).data(); });
				rennervate($keepsRemoved, $titles);
				$keeps.not($keepsRemoved).each(function () {
					var $k = $(this), data = $k.data();
					$k.addClass('mine detailed').toggleClass('selected', data.sel);
					$k.find('.keep-private').toggleClass('on', data.priv);
					$k.find('.keep-colls').append(data.$coll);
				}).removeData('sel priv $coll');
				updateKeepDetails();
				var collCounts = $keeps.find('.keep-coll').map(getDataId).get()
					.reduce(function (o, id) {o[id] = (o[id] || 0) + 1; return o; }, {});
				for (var collId in collCounts) {
					addTagCountAndDOM(collId, collCounts[collId]);
				}
			});
	}

	var $undo = $('.undo').on('click', '.undo-link', function () {
		var d = $undo.data();
		d.undo && d.undo();
		delete d.commit;
		hideUndo();
	});
	function showUndo(msg, undo, commit) {
		hideUndo();
		$undo.find('.undo-message').text(msg);
		$undo.show().data({undo: undo, commit: commit, timeout: setTimeout(hideUndo.bind(this, 'slow'), 30000)});
	}
	function hideUndo(duration) {
		var d = $undo.data();
		if (d.timeout) {
			clearTimeout(d.timeout);
			delete d.timeout;
			if (duration) {
				$undo.fadeOut(duration, expireUndo);
			} else {
				$undo.hide();
				expireUndo();
			}
		}
	}
	function expireUndo() {
		var d = $undo.data();
		d.commit && d.commit();
		delete d.undo;
		delete d.commit;
	}

	var emailTmpl = Handlebars.compile($('#email-address').html());

	function updateMe(data) {
		log('[updateMe]', data);
		me = data;
		kifiTracker.setUserInfo(me);
		$('.my-pic').css('background-image', 'url(' + formatPicUrl(data.id, data.pictureName, 200) + ')');
		$('.profile-image').css('background-image', 'url(' + formatPicUrl(data.id, data.pictureName, 200) + ')');
		$('.my-name').text(data.firstName + ' ' + data.lastName);
		$('.my-description').text(data.description || '\u00A0'); // nbsp

		var $firstNamePlace = $('.profile-placeholder-first-name').text(data.firstName);
		var $lastNamePlace = $('.profile-placeholder-last-name').text(data.lastName);

		$('.profile-first-name')
			.val(data.firstName)
			.outerWidth($firstNamePlace.outerWidth());

		$('.profile-last-name')
			.val(data.lastName)
			.outerWidth($lastNamePlace.outerWidth());

		var primary = getPrimaryEmail();
		var pendingPrimary = getPendingPrimaryEmail();

		var $unverified = $('.profile-email-address-unverified');
		$unverified.toggle(!pendingPrimary && primary && !primary.isVerified);

		$('.profile-email input').val(primary && primary.address || '');

		var $emails = $('.profile-email-address-list').empty();
		(me.emails || []).forEach(function (info, i, list) {
			$emails.append(emailTmpl({
				email: info.address,
				primary: i === 0 || list.length <= 1 || !!info.isPrimary,
				verified: !!info.isVerified,
				pendingPrimary: !!info.isPendingPrimary
			}));
		});

		var $pending = $('.profile-email-address-pending');
		if (pendingPrimary) {
			$('.profile-email-address-pending-email').text(pendingPrimary.address);
		}
		$pending.toggle(!!pendingPrimary);

	}

	function updateFriendRequests(n) {
		var $a = $('h3.my-friends>a'), $count = $a.find('.nav-count');
		if (n < 0) {
			n = Math.max(Number($count.text() || 0) + n, 0);
		}
		$count.add('.friend-req-count').text(n || '');
		$a[0].href = n ? 'friends/requests' : 'friends';
	}

	function hasExperiment(me, name, noAdmin) {
		var exp = me.experiments;
		if (exp) {
			return exp.indexOf(name) !== -1 || (!noAdmin && exp.indexOf('admin') !== -1);
		}
		return false;
	}

	function getNetworks() {
		return $.getJSON(xhrBase + '/user/networks', function (data) {
			myNetworks = data;
			var hasOtherNetworks = data.some(function (net) {
				return net.network !== 'fortytwo';
			});
			if (hasOtherNetworks) {
				$friendsTabPages.removeClass('no-networks');
			}
			else {
				$.getJSON(xhrBase + '/user/abooks', function (abooks) {
					$friendsTabPages.toggleClass('no-networks', !abooks.length);
				});
			}
		});
	}

	// load data for persistent (view-independent) page UI
	var promise = {
		me: repromiseMe(),
		myNetworks: getNetworks().promise(),
		myPrefs: $.getJSON(xhrBase + '/user/prefs', function (data) {
			myPrefs = data;
			if (myPrefs.site_left_col_width) {
				$('.left-col').animate({width: +myPrefs.site_left_col_width}, 120);
			}
		}).promise()
	};

	function refreshMe(data) {
		var url = xhrBase + '/user/me';
		if (data) {
			return $.postJson(url, data, updateMe);
		}
		return $.getJSON(url, updateMe);
	}

	function repromiseMe(data) {
		var p = refreshMe(data).promise().done(function (me) {
			me.fullname = me.fullname || (me.firstName ? (me.lastName ? me.firstName + ' ' + me.lastName : me.firstName) : (me.lastName || ''));
			return me;
		});
		if (promise) {
			promise.me = p;
		}
		return p;
	}

	repromiseMe();

	updateCollections();
	$.getJSON(xhrBase + '/user/friends/count', function (data) {
		updateFriendRequests(data.requests);
	});

	function getUriParam(name) {
		return decodeURI(((new RegExp(name + '=(.+?)(&|$)')).exec(location.search) || [,null])[1]);
	}

	var messages = {
		0: 'Welcome back!',
		1: 'Thank you for verifying your email address.',
		2: 'Bookmark import in progress. Reload the page to update.'
	};

	function showNotification(messageId) {
		var msg = messages[messageId];
		if (msg) {
			$('<div>').addClass('notification')
				.append($('<span>').addClass('notification-box').text(msg))
				.appendTo('.query-wrap')
				.show()
				.delay(5000)
				.fadeOut('slow');
		}
	}

	// render initial view
	$(window).trigger('statechange');

	// bind hover behavior later to avoid slowing down page load
	var friendCardTmpl = Tempo.prepare('kifi-fr-card-template');
	$('#kifi-fr-card-template').remove();
	$.getScript('assets/js/jquery-hoverfu.min.js').done(function () {
		$(document).hoverfu('.pic:not(.me)', function (configureHover) {
			var $a = $(this), id = $a.data('id'), $temp = $('<div>');
			friendCardTmpl.into($temp).append({
				name: $a.data('name'),
				picUri: formatPicUrl(id, $a.css('background-image').match(/\/([^\/]*)['"]?\)$/)[1], 200)
			});
			var $el = $temp.children().detach();
			configureHover($el, {
				position: {my: 'left-33 bottom-13', at: 'center top', of: $a, collision: 'flipfit flip', using: show},
				mustHoverFor: 400,
				canLeaveFor: 600,
				hideAfter: 4000,
				click: 'toggle'
			});
			$.getJSON(xhrBase + '/user/' + id + '/networks', function (networks) {
				for (var nw in networks) {
					log('[networks]', nw, networks[nw]);
					$el.find('.friend-nw-' + nw)
						.attr('href', networks[nw].connected || null);
				}
			});
		});
		function show(pos, o) {
			o.element.element.css(pos).addClass(o.horizontal + ' ' + o.vertical)
				.find('.kifi-fr-kcard-tri').css('left', Math.round(o.target.left - o.element.left + 0.5 * o.target.width));
		}
	});

	/* Email Verified */
	var emailVerifiedPending = false;
	var emailVerifiedView = false;
	var emailVerifiedViewDone = false;
	function checkEmailVerified() {
		if (emailVerifiedView) {
			return;
		}
		emailVerifiedView = true;

		var params = util.deparam((location.search || '').substring(1));
		if (params.m === '3' && params.email) {
			emailVerifiedPending = true;
			promise.me.done(function() {
				emailVerifiedPending = false;
				if (emailVerifiedViewDone) {
					return;
				}
				emailVerifiedViewDone = true;
				showEmailVerfied(me.firstName || me.lastName || '', params.email, function () {
					if (!$('html').data('kifi-ext')) {
						// no extension installed
						window.location = '/install';
					}
					else {
						initOnboarding();
					}
				});
			});
		}
		else {
			initOnboarding();
		}
	}

	function showEmailVerfied(name, email, callback) {
		var $dialog = $('.email-verified-dialog')
			.remove()
			.show()
			.find('.email-verified-user-name').text(name).end()
			.find('.email-verified-user-email').text(email).end()
			.dialog('show')
			.on('click', 'button', function () {
				$dialog.dialog('hide');
				$dialog = null;
			})
			.on('dialog.hide', function () {
				if (callback) {
					callback();
				}
			});
	}

	/* Onboarding */
	var onboardingViewed = false;
	function initOnboarding(force) {
		if (!force && (emailVerifiedPending || onboardingViewed)) {
			return;
		}
		promise.myPrefs.done(function () {
			if (!force && onboardingViewed) {
				return;
			}
			onboardingViewed = true;
			if (force || !myPrefs.onboarding_seen || myPrefs.onboarding_seen === 'false') {
				$('body').append('<iframe class="kifi-onboarding-iframe" src="/assets/onboarding.html" frameborder="0"></iframe>');
			}
			else {
				initBookmarkImport();
			}
		});
	}

	// onboarding.js is using this function
	window.getMe = function() {
		return promise.me.done(function (me) {
			me.pic200 = formatPicUrl(me.id, me.pictureName, 200);
			return me;
		});
	};

	// onboarding.js is using this function
	window.exitOnboarding = function () {
		$('.kifi-onboarding-iframe').remove();
		$.postJson(xhrBase + '/user/prefs', {
			onboarding_seen: 'true'
		}, function (data) {
			log('[prefs]', data);
		});
		initBookmarkImport();
	};

	/* Bookmark Import */
	var bookmarkImported = false;
	function initBookmarkImport() {
		if (bookmarkImported) {
			return;
		}
		bookmarkImported = true;
		log('[initBookmarkImport]');
		window.addEventListener('message', function (event) {
			if (event.origin === location.origin && event.data && event.data.bookmarkCount > 0) {
				showBookmarkImportDialog(event);
			}
		});
		window.postMessage('get_bookmark_count_if_should_import', '*'); // may get {bookmarkCount: N} reply message

		var $bookmarkImportDialog = $('.import-dialog').remove().show();

		//if (KF.dev) { showBookmarkImportDialog({ data: { bookmarkCount: 2 } }); }

		function showBookmarkImportDialog(event) {
			$bookmarkImportDialog.find('.import-bookmark-count').text(event.data.bookmarkCount);
			kifiTracker.view('bookmarkImport1');
			$bookmarkImportDialog.dialog('show').on('click', '.cancel-import,.import-dialog-x', function () {
				mixpanel.track('user_clicked_page', {
					type: 'bookmarkImport1',
					action: $(this).is('.cancel-import') ? 'cancel' : 'x',
					origin: window.location.origin
				});
				$bookmarkImportDialog.dialog('hide');
				$bookmarkImportDialog = null;
				// don't open again!
				event.source.postMessage('import_bookmarks_declined', event.origin);
			}).on('click', 'button.do-import', function () {
				mixpanel.track('user_clicked_page', {
					type: 'bookmarkImport1',
					action: 'approve',
					origin: window.location.origin
				});
				$bookmarkImportDialog.find('.import-step-1').hide();
				$bookmarkImportDialog.find('.import-step-2').show();
				kifiTracker.view('bookmarkImport2');
				$bookmarkImportDialog.on('click', 'button', function () {
					$bookmarkImportDialog.dialog('hide');
					$bookmarkImportDialog = null;
					mixpanel.track('user_clicked_page', {
						type: 'bookmarkImport2',
						action: 'done',
						origin: window.location.origin
					});
					window.location = "https://www.kifi.com/?m=2";
				});
				event.source.postMessage('import_bookmarks', event.origin);
			}).find('button').focus();
		}
	}
});
