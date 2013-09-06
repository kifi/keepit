var xhrDomain = 'https://api.kifi.com';
//xhrDomain = 'http://dev.ezkeep.com:9000';
var xhrBase = xhrDomain + '/site';
var xhrBaseEliza = xhrDomain.replace('api', 'eliza') + '/eliza/site';

var compareSearch = {usage: "search", sensitivity: "base"};
var compareSort = {numeric: true};

$.ajaxSetup({cache: true, crossDomain: true, xhrFields: {withCredentials: true}});

$.timeago.settings.localeTitle = true;
$.extend($.timeago.settings.strings, {
	seconds: "seconds",
	minute: "a minute",
	hour: "an hour",
	hours: "%d hours",
	month: "a month",
	year: "a year"});

!function() {
	$.fn.layout = function() {
		return this.each(forceLayout);
	};
	function forceLayout() {
		this.clientHeight;
	}

	$.fn.removeText = function() {
		return this.contents().filter(isText).remove().end().end();
	};
	function isText() {
		return this.nodeType === 3;
	}

	$.fn.dialog = function() {
		var args = Array.prototype.slice.apply(arguments);
		args.unshift(dialogMethods[args[0]]), args[1] = null;
		return this.each($.proxy.apply($, args));
	};
	var dialogMethods = {
		show: function() {
			var $d = $(this);
			if (!this.parentNode) {
				$d.appendTo('body').layout();
			}
			$d.addClass('showing');
			$(document).on('keydown.dialog', function(e) {
				if (e.keyCode === 27 && !e.isDefaultPrevented()) {
					e.preventDefault();
					$d.dialog('hide');
				}
			});
		},
		hide: function() {
			$(document).off('keydown.dialog');
			$(this).on('transitionend', function end(e) {
				if (e.target === this) {
					$(this).off('transitionend', end).detach();
				}
			}).removeClass('showing');
		}};
}();

$.postJson = function(uri, data, done) {
	return $.ajax({
		url: uri,
		type: 'POST',
		dataType: 'json',
		data: JSON.stringify(data),
		contentType: 'application/json',
		success: done || $.noop});
};

// detaches and reattaches nested template so it will work as an independent template
$.fn.prepareDetached = function(opts) {
	var el = this[0], p = el.parentNode, n = el.nextSibling, t;
	p.removeChild(el);
	t = Tempo.prepare(el, opts);
	p.insertBefore(el, n);
	return t;
};

$(function() {
	// bugzil.la/35168
	var $pageColInner = $(".page-col-inner"), winHeight = $(window).height();
	if ($pageColInner.get().some(function(el) {return el.offsetHeight != winHeight})) {
		setPageColHeights(winHeight);
		$(window).resize(setPageColHeights);
	}
	function setPageColHeights(h) {
		$pageColInner.css("height", h > 0 ? h : $(window).height());
	}

	var $leftCol = $(".left-col").resizable({
		delay: 10,
		handles: "e",
		minWidth: 240, // should match CSS
		maxWidth: 420,
		stop: function(e, ui) {
			console.log("[resizable:stop] saving");
			$.postJson(xhrBase + '/user/prefs', {site_left_col_width: String($leftCol.outerWidth())}, function(data) {
				console.log("[prefs]", data);
			});
		},
		zIndex: 1});
	$leftCol.find(".ui-resizable-handle").appendTo($leftCol.find(".page-col-inner"));

	var $subtitle = $(".subtitle"), subtitleTmpl = Tempo.prepare($subtitle);
	var $checkAll = $subtitle.find('.check-all').click(function() {
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
	}).hover(function() {
		var $text = $checkAll.next('.subtitle-text'), d = $text.data(), noun = searchResponse ? 'result' : 'Keep';
		if (d.n) {
			$checkAll.addClass('live');
			d.leaveText = $text.text();
			$text.text(($checkAll.hasClass('checked') ? 'Deselect ' : 'Select ') + (
				d.n == 1 ? 'the ' + noun + ' below' :
				 d.n == 2 ? 'both ' + noun + 's below' :
				 'all ' + d.n + ' ' + noun + 's below'));
		}
	}, function() {
		var $text = $(this).removeClass("live").next('.subtitle-text'), d = $text.data();
		if (d.leaveText) {
			$text.text(d.leaveText), delete d.leaveText;
		}
	});
	function updateSubtitleTextForSelection(numSel) {
		var $text = $checkAll.next('.subtitle-text'), d = $text.data();
		if (numSel) {
			if (!d.defText) {
				d.defText = d.leaveText || $text.text();
			}
			$text.text(numSel + ' ' + (searchResponse ? 'result' : 'Keep') + (numSel == 1 ? '' : 's') + ' selected');
		} else {
			$text.text(d.defText), delete d.defText;
		}
		delete d.leaveText;
	}

	$('.keep-colls,.keep-coll').removeText();
	var $fixedTitle = $('.keep-group-title-fixed');
	var $myKeeps = $("#my-keeps"), $results = $("#search-results"), keepsTmpl = Tempo.prepare($myKeeps)
	.when(TempoEvent.Types.ITEM_RENDER_COMPLETE, function(ev) {
		var $keep = $(ev.element).draggable(draggableKeepOpts);
		$keep.find(".pic").each(function() {
			this.style.backgroundImage = "url(" + this.getAttribute("data-pic") + ")";
			this.removeAttribute("data-pic");
		});
		$keep.find("time").timeago();
	}).when(TempoEvent.Types.RENDER_COMPLETE, function(ev) {
		console.log('[$myKeeps:RENDER_COMPLETE]', ev);
		$keepSpinner.hide();

		var $titles = ev.element === $myKeeps[0] ? addGroupHeadings() : $();
		$mainKeeps.toggleClass("grouped", $titles.length > 0);
		if ($titles.length) {
			for (var sT = $mainKeeps.find(".antiscroll-inner")[0].scrollTop, i = $titles.length; i;) {
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
		revert: "invalid",
		handle: ".handle",
		cancel: ".keep-checkbox",
		appendTo: "body",
		scroll: false,
		helper: function() {
			var $keep = $(this);
			if (!$keep.hasClass('selected')) {
				return $keep.clone().width($keep.css('width'));
			} else {
				var $keeps = $keep.parent().find(".keep.selected"), refTop = this.offsetTop;
				return $('<ul class=keeps-dragging>').width($keep.css('width')).height($keep.css('height'))
					.append($keeps.clone().each(function(i) {
						this.style.top = $keeps[i].offsetTop - refTop + "px";
					}));
			}
		},
		start: function() {
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
					scrollTimeout = scrollTimeout || setTimeout(scroll, scrollTimeoutMs)
					scrollPx = -10 + Math.max(-10, dy);
				} else if (scrollTimeout) {
					clearTimeout(scrollTimeout), scrollTimeout = null;
				}
				lastPageX = e.pageX;
				lastPageY = e.pageY;
			}
			var scrollEl = $collList.find('.antiscroll-inner')[0], scrollPx, lastPageX, lastPageY;
			var scrollTimeout, scrollTimeoutMs = 100, scrollTopMax = scrollEl.scrollHeight - scrollEl.clientHeight;
			function scroll() {
				console.log('[scroll] px:', scrollPx);
				var top = scrollEl.scrollTop, newTop = Math.max(0, Math.min(scrollTopMax, top + scrollPx));
				if (newTop != top) {
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
		}};

	function addGroupHeadings() {
		var today = new Date().setHours(0, 0, 0, 0), h = [];
		$myKeeps.find("time").each(function() {
			var $time = $(this);
			switch (Math.round((today - new Date($time.attr("datetime")).setHours(0, 0, 0, 0)) / 86400000)) {
			case 0:
				if (!h[0] && !(h[0] = $myKeeps.find('.keep-group-title.today')[0])) {
					h[0] = $('<li class="keep-group-title today">Today</li>').insertBefore($time.closest(".keep"))[0];
				}
				break;
			case 1:
				if (!h[1] && !(h[1] = $myKeeps.find('.keep-group-title.yesterday')[0])) {
					h[1] = $('<li class="keep-group-title yesterday">Yesterday</li>').insertBefore($time.closest(".keep"))[0];
				}
				break;
			case 2: case 3: case 4: case 5: case 6:
				if (!h[2] && !(h[2] = $myKeeps.find('.keep-group-title.week')[0])) {
					h[2] = $('<li class="keep-group-title week">Past Week</li>').insertBefore($time.closest(".keep"))[0];
				}
				break;
			default:
				if ((h[0] || h[1] || h[2]) && !(h[3] = $myKeeps.find('.keep-group-title.older')[0])) {
					h[3] = $('<li class="keep-group-title older">Older</li>').insertBefore($time.closest(".keep"))[0];
				}
				return false;
			}
		});
		console.log('[addGroupHeadings] h:', h);
		return $(h.filter(identity));
	}

	var $colls = $("#collections"), collTmpl = Tempo.prepare($colls).when(TempoEvent.Types.RENDER_COMPLETE, function(event) {
		collScroller.refresh();
		$colls.find(".collection").droppable(droppableCollectionOpts);
	});
	var droppableCollectionOpts = {
		accept: ".keep",
		greedy: true,
		tolerance: "pointer",
		hoverClass: "drop-hover",
		drop: function(event, ui) {
			addKeepsToCollection($(this).data("id"), ui.draggable.hasClass("selected") ? $main.find('.keep.selected') : ui.draggable);
		}};

	var collOptsTmpl = $('.page-coll-opts').removeText().prepareDetached({escape: false});
	var inCollTmpl = $('.page-coll-list').removeText().prepareDetached();
	var $detail = $('.detail'), detailTmpl = Tempo.prepare($detail);

	var $keepSpinner = $('.keeps-loading');
	var $loadMore = $('.keeps-load-more').click(function() {
		if (searchResponse) {
			doSearch();
		} else {
			loadKeeps($myKeeps.data("collId"));
		}
	});

	var me;
	var myNetworks;
	var myPrefs;
	var myKeepsCount;
	var collections;
	var searchResponse;
	var searchTimeout;
	var lastKeep;

	function identity(a) {
		return a;
	}

	function formatPicUrl(userId, pictureName, size) {
		return '//djty7jcqog9qu.cloudfront.net/users/' + userId + '/pics/' + size + '/' + pictureName;
	}

	function escapeHTMLContent(text) {
		return text.replace(/[&<>]/g, function(c) { return escapeHTMLContentMap[c]; });
	}
	var escapeHTMLContentMap = {'&': '&amp;', '<': '&lt;', '>': '&gt;'};

	function collIdAndName(id) {
		return {id: id, name: collections[id].name};
	}

	function showKeepDetails() {
		var $r = $detail.off("transitionend"), d;
		if ($r.css("display") == "block") {
			d = $r[0].getBoundingClientRect().left - $main[0].getBoundingClientRect().right;
		} else {
			$r.css({display: "block", visibility: "hidden", transform: ""});
			d = $(window).width() - $r[0].getBoundingClientRect().left;
		}
		$r.css({visibility: "", transform: "translate(" + d + "px,0)", transition: "none"});
		$r.layout();
		$r.css({transform: "", transition: "", "transition-timing-function": "ease-out"});
	}
	function hideKeepDetails() {
		var d = $(window).width() - $main[0].getBoundingClientRect().right;
		$detail.on("transitionend", function end(e) {
			if (e.target === this) {
				$(this).off("transitionend").css({display: "", transform: ""});
			}
		}).css({transform: "translate(" + d + "px,0)", "transition-timing-function": "ease-in"});
	}
	function updateKeepDetails() {
		hideUndo();
		var $detailed = $main.find('.keep.detailed');
		if ($detailed.length) {
			var o = {
				numKeeps: $detailed.length,
				howKept: $detailed.not(".mine").length ? null :
					$detailed.has(".keep-private.on").length == $detailed.length ? "pri" : "pub"};
			var collIds = $detailed.find('.keep-coll').map(getDataId).get();
			if ($detailed.length == 1) {
				var $keepLink = $detailed.find('.keep-title>a'), url = $keepLink[0].href;
				o.title = $keepLink.text();
				o.url = $keepLink[0].href;
				o.collections = collIds.map(collIdAndName);
				detailTmpl.render(o);
				$('.page-who-pics').append($detailed.find(".keep-who>.pic").clone());
				$('.page-who-text').html($detailed.find(".keep-who-text").html());
				var $pic = $('.page-pic'), $chatter = $('.page-chatter-messages');
				$.postJson(xhrBase + '/keeps/screenshot', {url: o.url}, function(data) {
					$pic.css('background-image', 'url(' + data.url + ')');
				}).error(function() {
					$pic.find('.page-pic-soon').addClass('showing');
				});
				$chatter.attr({'data-n': 0, 'data-locator': '/messages'});
				$.postJson(xhrBaseEliza + '/chatter', {url: o.url}, function(data) {
					$chatter.attr({
						'data-n': data.threads || 0,
						'data-locator': '/messages' + (data.threadId ? '/' + data.threadId : '')});
				});
			} else { // multiple keeps
				var collCounts = collIds.reduce(function(o, id) {o[id] = (o[id] || 0) + 1; return o}, {});
				o.collections = Object.keys(collCounts).sort(function(id1, id2) {return collCounts[id1] - collCounts[id2]}).map(collIdAndName);
				detailTmpl.render(o);
			}
			inCollTmpl.into($detail.find('.page-coll-list')[0]).render(o.collections);
			showKeepDetails();
		} else {
			hideKeepDetails();
		}
	}

	// profile

	$('.my-pic').click(function(e) {
		if (e.which === 1 && $('body').attr('data-view') === 'profile') {
			e.preventDefault();
			if (History.getCurrentIndex()) {
				History.back();
			} else {
				navigate('');
			}
		}
	});
	var profileTmpl = Tempo.prepare("profile-template");
	function showProfile() {
		$.when(promise.me, promise.myNetworks).done(function () {
			profileTmpl.render(me);
			$('body').attr("data-view", "profile");
			$('.left-col .active').removeClass("active");
			$('.profile').on('keydown keypress keyup', function (e) {
				e.stopPropagation();
			});
			$('.profile .edit').click(function () {
				var $inputs = $(this).closest('.edit-container').addClass('editing').find('.editable').each(function () {
					var $this = $(this);
					var value = $this.text();
					var maxlen = $this.data('maxlength');
					$('<input>').val(value).attr('maxlength', maxlen).keyup(function (e) {
						if (maxlen) {
							$(this).closest('.editable').attr('data-chars-left', maxlen - $(this).val().length);
						}
						if (e.keyCode === 13) {
							$(this).closest('.edit-container').find('.save').click();
						} else if (e.which === 27) {
							$(this).closest('.edit-container').removeClass('editing').find('.editable').each(function () {
								var $this = $(this);
								var prop = $this.data("prop");
								if (prop == 'email') {
									$this.text(me.emails[0]);
								} else {
									$this.text(me[prop]);
								}
							});
						}
					}).appendTo($this.empty()).keyup();
				}).find('input');
				$inputs[0].focus();
				$inputs.on('keypress paste change', function () {
					var minChars = 3, scale = 1.25;
					var $this = $(this);
					var size = Math.max(Math.ceil($this.val().length * scale), minChars);
					$this.css('width', 'auto').attr('size', size);
				}).keypress();
			});
			$('.profile .save').click(function () {
				var props = {};
				var $editContainer = $(this).closest('.edit-container');
				$editContainer.find('.editable').each(function () {
					var $this = $(this);
					var value = $this.find('input').val();
					$this.text(value);
					props[$this.data('prop')] = value;
				});
				if (props.email) {
					props.emails = [props.email];
					delete props.email;
				}
				var $save = $editContainer.find('.save')
				var saveText = $save.text();
				$save.text('Saving...');
				$.postJson(xhrBase + '/user/me', props, function(data) {
					$editContainer.removeClass('editing');
					$save.text(saveText);
					updateMe(data);
				}).error(function() {
					showMessage('Uh oh! A bad thing happened!');
					$save.text(saveText);
				});
			});
			$('.profile .networks li').each(function () {
				var $this = $(this);
				var name = $this.data('network');
				if (!name) return;
				var $a = $this.find('a.profile-nw');
				var networkInfo = myNetworks.filter(function (nw) {
					return nw.network === name;
				})[0];
				var postLink = function (e) {
					e.preventDefault();
					$('<form>')
						.attr('action', xhrDomain + $(this).data('action'))
						.attr('method', 'post')
						.appendTo('body')
						.submit()
						.remove();
				};
				if (networkInfo) {
					$a.attr('href', networkInfo.profileUrl).attr('title', 'View profile');
					if (myNetworks.length > 1) {
						$('<a>').addClass('disconnect').text('Unlink')
							.attr('href', 'javascript:')
							.data('action', '/disconnect/' + name)
							.click(postLink)
							.appendTo($this);
					}
				} else {
					$a.addClass('not-connected').attr('title', 'Click to connect')
						.attr('href', 'javascript:')
						.data('action', '/link/' + name)
						.click(postLink);
					$('<a>').attr('title', 'Click to connect').addClass('connect').text('Connect')
						.attr('href', 'javascript:')
						.data('action', '/link/' + name)
						.click(postLink)
						.appendTo($this);
				}
			});
		});
	}

	// Friends Tabs/Pages

	var $friends = $('.friends');
	var $friendsTabs = $friends.find('.friends-tabs>a');
	var $friendsTabPages = $friends.find('.friends-page');

	function showFriends(path) {
		$('body').attr('data-view', 'friends');
		$('.left-col .active').removeClass('active');
		$('.my-friends').addClass('active');
		var $tab = $friendsTabs.filter('[data-href="' + path + '"]').removeAttr('href');
		if ($tab.length) {
			$friendsTabs.not($tab).filter(':not([href])').each(function() {this.href = $(this).data('href')});
			[prepFriendsTab, prepInviteTab, prepRequestsTab][$tab.index()]();
			$friendsTabPages.hide().filter('[data-href="' + path + '"]').show().find('input').focus();
		} else {
			navigate('friends', {replace: true});
		}
	}

	// All kifi Friends

	var $friendsFilter = $('.friends-filter').on('input', function() {
		var val = $.trim(this.value);
		if (val) {
			var prefixes = val.split(/\s+/);
			$friendsList.find('.friend').filter(function() {
				var $f = $(this), o = $f.data('o'), names = $.trim(o.firstName + ' ' + o.lastName).split(/\s+/);
				$f.toggleClass('no-match', !prefixes.every(function(p) {
					return names.some(function(n) {return 0 === p.localeCompare(n.substring(0, p.length), undefined, compareSearch)});
				}));
			});
		} else {
			$friendsList.find('.no-match').removeClass('no-match');
		}
	});
	var $friendsList = $('#friends-list').antiscroll({x: false, width: '100%'})
	.on('mouseover', '.friend-status', function() {
		var $a = $(this), o = $a.closest('.friend').data('o');
		$(this).nextAll('.friend-action-desc').text({
			unfriended: 'Add ' + o.firstName + ' as a friend',
			requested: 'Cancel friend request',
			'': 'Unfriend ' + o.firstName}[o.state]);
	}).on('click', '.friend-status', function() {
		var $a = $(this), o = $a.closest('.friend').data('o'), xhr;
		switch (o.state) {
			case 'unfriended':
				xhr = $.post(xhrBase + '/user/' + o.id + '/friend', function(data) {
					o.state = data.acceptedRequest ? '' : 'requested';
				}); break;
			case 'requested':
				xhr = $.post(xhrBase + '/user/' + o.id + '/cancelRequest', function(data) {
					o.state = 'unfriended';
				}).error(function() {
					if (xhr && xhr.responseText && JSON.parse(xhr.responseText).alreadyAccepted) {
						o.state = 'friend';
					}
				}); break;
			default:
				xhr = $.post(xhrBase + '/user/' + o.id + '/unfriend', function(data) {
					o.state = 'unfriended';
				});
		}
		xhr.always(function() {
			$a.removeAttr('href').closest('.friend-actions').removeClass('requested unfriended').addClass(o.state);
			$a.nextAll('.friend-action-desc').text('');
		});
	}).on('mouseover', '.friend-mute', function() {
		var $a = $(this), o = $a.closest('.friend').data('o');
		$a.nextAll('.friend-action-desc').text(o.searchFriend ?
			'Don’t show ' + o.firstName + '’s keeps in my search results' :
			'Show ' + o.firstName + '’s keeps in my search results');
	}).on('click', '.friend-mute[href]', function() {
		var $a = $(this), o = $a.closest('.friend').data('o'), mute = !!o.searchFriend;
		$.post(mute ?
				xhrBase + '/user/' + o.id + '/exclude' :
				xhrBase + '/user/' + o.id + '/include', function() {
			o.searchFriend = !mute;
			$a.removeAttr('href').toggleClass('muted', mute).nextAll('.friend-action-desc').text('');
		});
	}).on('mouseout', '.friend-status,.friend-mute', function() {
		$(this).attr('href', 'javascript:').nextAll('.friend-action-desc').empty();
	});
	$friendsList.find('.antiscroll-inner').scroll(function() {
		$friendsList.prev().toggleClass('scrolled', this.scrollTop > 0);
	});
	var friendsScroller = $friendsList.data("antiscroll");
	$(window).resize(friendsScroller.refresh.bind(friendsScroller));
	var friendsTmpl = Tempo.prepare($friendsList).when(TempoEvent.Types.ITEM_RENDER_COMPLETE, function(ev) {
		var o = ev.item, $f = $(ev.element).data("o", o), url;
		for (var nw in o.networks) {  // TODO: move networks to template (Tempo seems broken)
			if (o.networks[nw].connected && (url = o.networks[nw].profileUrl)) {
				$f.find('.friend-nw-' + nw).attr('href', url);
			}
		}
	}).when(TempoEvent.Types.RENDER_COMPLETE, function() {
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
		.done(function(a0, a1) {
			var friends = a0[0].friends, requests = a1[0];
			var requested = requests.reduce(function(o, u) {o[u.id] = true; return o}, {});
			console.log('[prepFriendsTab] friends:', friends.length, 'req:', requests.length);
			for (var f, i = 0; i < friends.length; i++) {
				f = friends[i];
				f.picUri = formatPicUrl(f.id, f.pictureName, 200);
				f.state = requested[f.id] ? 'requested' : f.unfriended ? 'unfriended' : '';
				delete f.unfriended;
			}
			friends.sort(function(f1, f2) {
				return f1.firstName.localeCompare(f2.firstName, undefined, compareSort) ||
				       f1.lastName.localeCompare(f2.lastName, undefined, compareSort) ||
				       f1.id.localeCompare(f2.id, undefined, compareSort);
			});
			friendsTmpl.render(friends);
		});
	}

	// Friend Invites

	var $nwFriends = $('.invite-friends').antiscroll({x: false, width: '100%'});
	$nwFriends.find(".antiscroll-inner").scroll(function() { // infinite scroll
		var sT = this.scrollTop, sH = this.scrollHeight;
		// tweak these values as desired
		const offset = sH / 4, toFetch = 40;
		if (!$nwFriendsLoading.is(':visible') && this.clientHeight + sT > sH - offset) {
			console.log('loading more friends');
			prepInviteTab(toFetch);
		}
	});
	var nwFriendsScroller = $nwFriends.data("antiscroll");
	$(window).resize(nwFriendsScroller.refresh.bind(nwFriendsScroller));  // TODO: throttle, and only bind while visible
	var nwFriendsTmpl = Tempo.prepare($nwFriends).when(TempoEvent.Types.RENDER_COMPLETE, function() {
		$nwFriendsLoading.hide();
		nwFriendsScroller.refresh();
	});
	var inviteFilterTmpl = Tempo.prepare($('.above-invite-friends'))
	var $nwFriendsLoading = $('.invite-friends-loading');
	var friendsTimeout;
	function filterFriends() {
		clearTimeout(friendsTimeout);
		$nwFriends.find('ul').empty();
		friendsTimeout = setTimeout(prepInviteTab, 200);
	}

	var invitesUpdatedAt;
	function updateInviteCache() {
		invitesUpdatedAt = Date.now();
	}
	updateInviteCache();

	const friendsToShow = 40;
	const friendsShowing = [];
	var moreFriends = true;
	var invitesLeft;
	function prepInviteTab(moreToShow) {
		if (moreToShow && !moreFriends) return;
		moreFriends = true;
		var network = $('.invite-filters').attr('data-nw-selected') || undefined;
		var search = $('.invite-filter').val() || undefined;
		$nwFriendsLoading.show();
		var opts = {
			limit: moreToShow || friendsToShow,
			after: moreToShow ? friendsShowing[friendsShowing.length - 1].value : undefined,
			search: search,
			network: network,
			updatedAt: invitesUpdatedAt
		};
		$.getJSON(xhrBase + '/user/socialConnections', opts, function(friends) {
			console.log('[prepInviteTab] friends:', friends.length);
			var nw = $('.invite-filters').attr('data-nw-selected') || undefined;
			var filter = $('.invite-filter').val() || undefined;
			if (filter != search || nw != network) return;
			if (friends.length < moreToShow) {
				moreFriends = false;
			}
			if (!moreToShow) {
				nwFriendsTmpl.clear();
				friendsShowing.length = 0;
			}
			friendsShowing.push.apply(friendsShowing, friends);
			nwFriendsTmpl.append(friends);
			inviteFilterTmpl.render({results: friendsShowing.length, filter: filter});
		});
		$.getJSON(xhrBase + '/user/inviteCounts', { updatedAt: invitesUpdatedAt }, function (invites) {
			invitesLeft = invites.left;
			$('.num-invites').text(invitesLeft);
		});
	}
	$('.invite-filters>a').click(function () {
		$(this).parent().attr('data-nw-selected', $(this).data('nw') || null);
		filterFriends();
	});
	var $noInvitesDialog = $('.no-invites-dialog').detach().show()
	.on('click', '.more-invites', function(e) {
		e.preventDefault();
		$noInvitesDialog.dialog('hide');
		$.postJson('/site/user/needMoreInvites', {}, function (data) {
			$noInvitesDialog.dialog('hide');
		});
	});
	var $inviteMessageDialog = $('.invite-message-dialog').detach().show()
	.on('submit', 'form', function(e) {
		e.preventDefault();
		$.post(this.action, $(this).serialize()).complete(function(xhr) {
			if (xhr.status >= 400) {
				console.log('error sending invite:', xhr);
			} else {
				console.log('sent invite');
				$inviteMessageDialog.dialog('hide');
			}
			updateInviteCache();
			prepInviteTab();
		});
	}).on('click', '.invite-cancel', function(e) {
		e.preventDefault();
		$inviteMessageDialog.dialog('hide');
	});
	var inviteMessageDialogTmpl = Tempo.prepare($inviteMessageDialog);
	$('.invite-filter').on('input', filterFriends);
	$('.invite-friends').on('click', '.invite-button', function() {
		if (!invitesLeft) {
			$noInvitesDialog.dialog('show');
			return;
		}
		var $friend = $(this).closest('.invite-friend'), fullSocialId = $friend.data('value');
		// TODO(greg): figure out why this doesn't work cross-domain
		if (/^facebook/.test(fullSocialId)) {
			window.open("about:blank", fullSocialId, "height=640,width=1060,left=200,top=200", false);
			$('<form method=POST action=/invite target="' + fullSocialId + '" style="position:fixed;height:0;width:0;left:-99px">')
			.append('<input type=hidden name=fullSocialId value="' + fullSocialId + '">')
			.appendTo('body').submit().remove();
		} else if (/^linkedin/.test(fullSocialId)) {
			inviteMessageDialogTmpl.render({fullSocialId: fullSocialId, label: $friend.find('.invite-name').text()});
			$inviteMessageDialog.dialog('show');
		}
	});

	// Friend Requests

	var $friendReqs = $('.friend-reqs').antiscroll({x: false, width: '100%'});
	$friendReqs.find('.antiscroll-inner').scroll(function() {
		$friendReqs.prev().toggleClass('scrolled', this.scrollTop > 0);
	}).on('click', '.friend-req-y[href],.friend-req-n[href]', function() {
		var $a = $(this), id = $a.closest('.friend-req').data('id'), accepting = $a.hasClass('friend-req-y');
		$.post(accepting ?
				xhrBase + '/user/' + id + '/friend' :
				xhrBase + '/user/' + id + '/ignoreRequest', function() {
			$a.closest('.friend-req-act').addClass('done');
			$a.closest('.friend-req').find('.friend-req-q').text(
				accepting ? 'Accepted as your kifi friend' : 'Friend request ignored');
			updateFriendRequests(-1);
		}).error(function() {
			$a.siblings('a').addBack().attr('href', 'javascript:');
		});
		$a.siblings('a').addBack().removeAttr('href');
	});
	var friendReqsScroller = $friendReqs.data("antiscroll");
	$(window).resize(friendReqsScroller.refresh.bind(friendReqsScroller));
	var friendReqsTmpl = Tempo.prepare($friendReqs).when(TempoEvent.Types.RENDER_COMPLETE, function() {
		$friendReqsLoading.hide();
		friendReqsScroller.refresh();
	});
	var $friendReqsLoading = $('.friend-reqs-loading');
	function prepRequestsTab() {
		$.getJSON(xhrBase + '/user/incomingFriendRequests', function(reqs) {
			console.log('[prepRequestsTab] req:', reqs.length);
			for (var r, i = 0; i < reqs.length; i++) {
				r = reqs[i];
				r.picUri = formatPicUrl(r.id, r.pictureName, 200);
			}
			$('.friend-reqs-status').text('You have ' + (reqs.length || 'no pending') + ' friend request' + (reqs.length == 1 ? '' : 's') + '.');
			friendReqsTmpl.render(reqs);
		});
	}

	function showBlog() {
		$('body').attr('data-view', 'blog');
		$('.left-col .active').removeClass('active');
		var $blog = $('iframe.blog');
		if (!$blog.attr('src')) {
			$blog.attr('src', 'http://kifiupdates.tumblr.com');
		}
	}

	function doSearch(q) {
		if (q) {
			searchResponse = null;
		} else {
			q = searchResponse.query;
		}
		console.log("[doSearch] " + (searchResponse ? "more " : "") + "q:", q);
		$('.left-col .active').removeClass('active');
		$('body').attr("data-view", "search");
		if (!searchResponse) {
			subtitleTmpl.render({searching: true});
			$checkAll.removeClass('live checked');
			$myKeeps.detach().find('.keep').removeClass('selected detailed');
			$fixedTitle.empty().removeData();
		}
		$keepSpinner.show();
		$loadMore.addClass('hidden');

		$query.attr("data-q", q);
		if (!$query.val()) $query.val(q).focus().closest($queryWrap).removeClass('empty');
		var context = searchResponse && searchResponse.context;
		$.getJSON(xhrDomain + '/search', {q: q, f: "a", maxHits: 30, context: context}, function(data) {
			updateCollectionsIfAnyUnknown(data.hits);
			$.when(promise.me, promise.collections).done(function() {
				searchResponse = data;
				var numShown = data.hits.length + (context ? $results.find(".keep").length : 0);
				subtitleTmpl.render({numShown: numShown, query: data.query});
				if (numShown) $checkAll.addClass('live');
				data.hits.forEach(prepHitForRender);
				if (context) {
					keepsTmpl.into($results[0]).append(data.hits);
				} else {
					keepsTmpl.into($results[0]).render(data.hits);
					hideKeepDetails();
				}
				$checkAll.removeClass('checked');
			});
		});
	}

	function prepHitForRender(hit) {
		$.extend(hit, hit.bookmark);
		hit.me = me;
		hit.keepers = hit.users;
		hit.others = hit.count - hit.users.length - (hit.isMyBookmark && !hit.isPrivate ? 1 : 0);
		if (hit.collections) prepKeepCollections(hit.collections);
	}

	function prepKeepForRender(keep) {
		keep.isMyBookmark = true;
		keep.me = me;
		prepKeepCollections(keep.collections);
	}

	function prepKeepCollections(colls) {
		for (var i = 0; i < colls.length; i++) {
			colls[i] = collIdAndName(colls[i]);
		}
	}

	function addNewKeeps() {
		if (!$myKeeps[0].parentNode) return;  // in search
		var params = {}, keepId = $myKeeps.find('.keep').first().data('id');
		if (keepId) {
			params.after = keepId;
		}
		if ($('.left-col h3.active').is('.collection')) {
			params.collection = $('.left-col h3.active').data('id');
		}
		console.log("[anyNewKeeps] fetching", params);
		$.getJSON(xhrBase + '/keeps/all', params, function(data) {
			updateCollectionsIfAnyUnknown(data.keeps);
			$.when(promise.collections).done(function() {
				var keepIds = $myKeeps.find('.keep').map(getDataId).get().reduce(function(ids, id) {ids[id] = true; return ids}, {});
				var keeps = data.keeps.filter(function(k) {return !keepIds[k.id]});
				keeps.forEach(prepKeepForRender);
				keepsTmpl.into($myKeeps[0]).prepend(keeps);
				// TODO: insert this group heading if not already there
				$myKeeps.find('.keep-group-title.today').prependTo($myKeeps);
			});
		});
	}

	function showMyKeeps(collId) {
		collId = collId || null;
		var $h3 = $(".left-col h3");
		$h3.filter(".active").removeClass("active");
		$h3.filter(collId ? "[data-id='" + collId + "']" : ".my-keeps").addClass("active");

		var fromSearch = $('body').attr("data-view") == "search";
		$('body').attr("data-view", "mine");
		$mainHead.find("h1").text(collId ? collections[collId].name : "Browse your Keeps");

		$results.empty();
		$query.val('').removeAttr("data-q");
		$queryWrap.addClass('empty');
		$fixedTitle.empty().removeData();
		if (fromSearch) {
			searchResponse = null;
			$checkAll.removeClass('checked');
		}
		if (!$myKeeps[0].parentNode) {
			$myKeeps.insertAfter($results);
		}

		if ($myKeeps.data("collId") != collId || !("collId" in $myKeeps.data())) {
			$myKeeps.data("collId", collId).empty();
			lastKeep = null;
			hideKeepDetails();
			loadKeeps(collId);
		} else {
			var numShown = $myKeeps.find(".keep").length;
			subtitleTmpl.render({
				numShown: numShown,
				numTotal: collId ? collections[collId].keeps : myKeepsCount,
				collId: collId || undefined});
			$checkAll.toggleClass('live', numShown > 0).removeClass('checked');
			addNewKeeps();
		}
	}

	function loadKeeps(collId) {
		if (lastKeep != "end") {
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
			console.log("Fetching %d keeps %s", params.count, lastKeep ? "before " + lastKeep : "");
			$.getJSON(xhrBase + '/keeps/all', params, function withKeeps(data) {
				updateCollectionsIfAnyUnknown(data.keeps);
				$.when(promise.me, promise.collections).done(function() {
					var numShown = $myKeeps.find(".keep").length + data.keeps.length;
					subtitleTmpl.render({
						numShown: numShown,
						numTotal: collId ? collections[collId].keeps : myKeepsCount,
						collId: collId || undefined});
					$checkAll.toggleClass('live', numShown > 0);
					$keepSpinner.hide();
					if (!data.keeps.length) {  // no more
						lastKeep = "end";
					} else {
						data.keeps.forEach(prepKeepForRender);
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

	function updateNumKeeps() {
		$.getJSON(xhrBase + '/keeps/count', function(data) {
			$('.left-col .my-keeps .nav-count').text(myKeepsCount = data.numKeeps);
		});
	}

	function updateCollections() {
		promise.collections = $.getJSON(xhrBase + '/collections/all', {sort: "user"}, function(data) {
			collTmpl.render(data.collections);
			collections = data.collections.reduce(function(o, c) {o[c.id] = c; return o}, {});
		}).promise();
	}

	function updateCollectionsIfAnyUnknown(keeps) {
		if (collections && keeps.some(function(k) {return k.collections && k.collections.some(function(id) {return !collections[id]})})) {
			updateCollections();
		}
	}

	function showMessage(msg) {
		$.fancybox($('<p>').text(msg));
	}

	function getDataId() {
		return $(this).data("id");
	}

	var $collMenu = $("#coll-menu")
	.on("mouseover", "a", function() {
		$(this).addClass("hover");
	}).on("mouseout", "a", function() {
		$(this).removeClass("hover");
	}).on("mouseup mousedown", ".coll-remove", function(e) {
		if (e.which > 1 || !$collMenu.hasClass('showing')) return;
		hideCollMenu();
		var $coll = $collMenu.closest(".collection");
		var collId = $coll.data("id");
		console.log("Removing collection", collId);
		$.postJson(xhrBase + '/collections/' + collId + '/delete', {}, function(data) {
			delete collections[collId];
			$coll.slideUp(80, $.fn.remove.bind($coll));
			if ($myKeeps.data("collId") === collId) {
				$myKeeps.removeData("collId");
				showMyKeeps();
			}
			var $keepColl = $main.find(".keep-coll[data-id=" + collId + "]");
			if ($keepColl.length) $keepColl.css("width", $keepColl[0].offsetWidth);
			var $pageColl = $detail.find(".page-coll[data-id=" + collId + "]");
			if ($pageColl.length) $pageColl.css("width", $pageColl[0].offsetWidth);
			$keepColl.add($pageColl).layout().on("transitionend", removeIfThis).addClass("removed");
		}).error(showMessage.bind(null, 'Could not delete collection, please try again later'));
	}).on("mouseup mousedown", ".coll-rename", function(e) {
		if (e.which > 1 || !$collMenu.hasClass('showing')) return;
		hideCollMenu();
		var $coll = $collMenu.closest(".collection").addClass("renaming").each(function() {
			var scrEl = $collList.find(".antiscroll-inner")[0], oT = this.offsetTop;
			if (scrEl.scrollTop > oT) {
				scrEl.scrollTop = oT;
			}
		});
		var $name = $coll.find(".view-name"), name = $name.text();
		var $in = $("<input type=text placeholder='Type new collection name'>").val(name).data("orig", name);
		$name.empty().append($in);
		setTimeout(function() {
			$in[0].setSelectionRange(0, name.length, "backward");
			$in[0].focus();
		});
		$in.on('blur keydown', function(e) {
			if (e.which === 13 || e.type === "blur") { // 13 is Enter
				var oldName = $in.data("orig");
				var newName = $.trim(this.value) || oldName;
				if (newName !== oldName) {
					var collId = $coll.addClass("renamed").data("id");
					$.postJson(xhrBase + '/collections/' + collId + '/update', {name: newName}, function() {
						collections[collId].name = newName;
						if ($myKeeps.data("collId") === collId) {
							$main.find("h1").text(newName);
						}
					}).error(function() {
						showMessage('Could not rename collection, please try again later');
						$name.text(oldName);
					});
				}
				exitRename(newName);
			} else if (e.which === 27) {
				exitRename();
			}
		});
		function exitRename(name) {
			name = name || $in.data("orig");
			$in.remove();
			$name.text(name);
			$coll.on("transitionend", function end(e) {
				if (e.target === this) {
					$coll.off("transitionend", end).removeClass("renamed");
				}
			}).addClass("renamed").removeClass("renaming");
		}
	});
	function hideCollMenu() {
		console.log("[hideCollMenu]");
		document.removeEventListener("mousedown", $collMenu.data("docMouseDown"), true);
		$collMenu.removeData("docMouseDown").one('transitionend', function() {
			$collMenu.detach().find(".hover").removeClass("hover");
		}).removeClass('showing')
		.closest(".collection").removeClass("with-menu");
	}

	$(document).keydown(function(e) {  // auto focus on search field when starting to type anywhere on the document
		if (!$(e.target).is('input,textarea') && e.which >= 48 && e.which <= 90 && !e.ctrlKey && !e.metaKey && !e.altKey) {
			$query.focus();
		}
	}).on('click', 'a[href]', function(e) {
		var href;
		if (!e.isDefaultPrevented() && ~(href = this.getAttribute('href')).search(inPageNavRe)) {
			e.preventDefault();
			navigate(href);
		}
	});
	var inPageNavRe = /^(?:$|[a-z0-9]+(?:$|\/))/i;

	var baseUriRe = new RegExp('^' + ($('base').attr('href') || ''));
	$(window).on('statechange anchorchange', function(e) {
		hideUndo();
		var state = History.getState();
		var hash = state.hash.replace(baseUriRe, '').replace(/^\.\//, '').replace(/[?#].*/, '');
		var parts = hash.split('/');
		console.log('[' + e.type + ']', hash, state);
		switch (parts[0]) {
			case '':
				navigate('');
				showMyKeeps();
				break;
			case 'collection':
				$.when(promise.collections).done(function() {
					var collId = parts[1];
					if (collections[collId]) {
						if (collId !== $collList.find('.collection.active').data('id')) {
							showMyKeeps(collId);
						}
					} else {
						showMessage('Sorry, unable to view this collection.');
						e.preventDefault();
					}
				});
				break;
			case 'search':
				doSearch(decodeURIComponent(queryFromUri(state.hash)));
				break;
			case 'profile':
				showProfile();
				break;
			case 'friends':
				$.when(promise.me).done(function() {
					if (parts[1] === 'invites' && !canInvite()) {
						navigate('friends', {replace: true});
					} else {
						showFriends(hash);
					}
				});
				break;
			case 'blog':
				showBlog();
				break;
			default:
				return;
		}
		hideKeepDetails();
	});

	function navigate(uri, opts) {
		var baseUri = document.baseURI;
		if (uri.substr(0, baseUri.length) == baseUri) {
			uri = uri.substr(baseUri.length);
		}
		var title, kind = uri.match(/[\w-]*/)[0];
		console.log('[navigate]', uri, opts || '', kind);
		switch (kind) {
			case '':
				title = 'Your Keeps';
				break;
			case 'collection':
				title = collections[uri.substr(kind.length + 1)].name;
				break;
			case 'search':
				title = queryFromUri(uri);
				break;
			case 'profile':
				title = 'Profile';
				break;
			case 'friends':
				title = {friends: 'Friends', 'friends/invite': 'Invite Friends', 'friends/requests': 'Friend Requests'}[uri];
				break;
			case 'blog':
			  title = 'Updates and Features'
			  break;
		}
		History[opts && opts.replace ? 'replaceState' : 'pushState'](null, 'kifi.com • ' + title, uri);
	}

	function queryFromUri(uri) {
		return uri.replace(/.*?[?&]q=([^&]*).*/, '$1').replace(/\+/g, ' ');
	}

	var $main = $(".main").on("mousedown", ".keep-checkbox", function(e) {
		e.preventDefault();  // avoid starting selection
	}).on("click", ".keep-title>a", function(e) {
		e.stopPropagation();
	}).on("click", ".keep", function(e) {
		var $keep = $(this), $keeps = $main.find(".keep"), $el = $(e.target);
		if ($el.hasClass("keep-checkbox") || $el.hasClass("handle")) {
			$keep.toggleClass("selected");
			var $selected = $keeps.filter(".selected");
			$checkAll.toggleClass("checked", $selected.length == $keeps.length);
			updateSubtitleTextForSelection($selected.length);
			if ($selected.length == 0 ||
			    $selected.not(".detailed").addClass("detailed").length +
			    $keeps.filter(".detailed:not(.selected)").removeClass("detailed").length == 0) {
				return;  // avoid redrawing same details
			}
		} else if ($el.hasClass("pic") || $el.hasClass('keep-coll-a')) {
			return;
		} else if ($keep.hasClass("selected")) {
			$keeps.filter(".selected").toggleClass("detailed");
			$keeps.not(".selected").removeClass("detailed");
		} else if ($keep.hasClass("detailed")) {
			$keep.removeClass("detailed");
			$keeps.filter(".selected").addClass("detailed");
		} else {
			$keeps.removeClass("detailed");
			$keep.addClass("detailed");
		}
		updateKeepDetails();
	});
	var $mainHead = $(".main-head");
	var $mainKeeps = $(".main-keeps").antiscroll({x: false, width: "100%"});
	$mainKeeps.find(".antiscroll-inner").scroll(function() { // infinite scroll
		var sT = this.scrollTop;
		if ($keepSpinner.css('display') == 'none' && this.clientHeight + sT > this.scrollHeight - 300) {
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
			$mainKeeps.removeClass("scrolled");
		} else {
			$mainKeeps.toggleClass("scrolled", sT > 0);
		}
	});
	var mainScroller = $mainKeeps.data("antiscroll");
	$(window).resize(mainScroller.refresh.bind(mainScroller));

	var splashScroller = $(".splash").antiscroll({x: false, width: "100%"}).data("antiscroll");
	$(window).resize(splashScroller.refresh.bind(splashScroller));

	var $queryWrap = $('.query-wrap');
	$queryWrap.focusin($.fn.addClass.bind($queryWrap, 'focus'));
	$queryWrap.focusout($.fn.removeClass.bind($queryWrap, 'focus'));
	var $query = $("input.query").on("keydown input", function(e) {
		console.log("[clearTimeout]", e.type);
		clearTimeout(searchTimeout);
		var val = this.value, q = $.trim(val);
		$queryWrap.toggleClass("empty", !val);
		if (q === ($query.attr("data-q") || "")) {
			console.log("[query:" + e.type + "] no change");
		} else if (!q) {
			navigate('');
		} else if (!e.which || e.which == 13) { // Enter
			var uri = 'search?q=' + encodeURIComponent(q).replace(/%20/g, '+');
			if (e.which) {
				navigate(uri);
			} else {
				searchTimeout = setTimeout(navigate.bind(null, uri), 500);  // instant search
			}
		}
	});
	$('.query-mag').mousedown(function(e) {
		if (e.which == 1) {
			e.preventDefault();
			$query.focus();
		}
	});
	$('.query-x').click(function() {
		$query.val('').focus().triggerHandler('input');
	});

	var $collList = $("#collections-list")
	.each(function() {this.style.top = this.offsetTop + "px"})
	.addClass("positioned")
	.antiscroll({x: false, width: "100%", autoHide: false})
	.sortable({
		axis: "y",
		items: ".collection",
		cancel: ".coll-tri,#coll-menu,.renaming",
		opacity: .6,
		placeholder: "sortable-placeholder",
		beforeStop: function(event, ui) {
			// update the collection order
			$.postJson(xhrBase + '/collections/ordering', $(this).find(".collection").map(getDataId).get(), function(data) {
				console.log(data);
			}).error(function() {
				showMessage('Could not reorder the collections, please try again later');
				// TODO: revert the re-order in the DOM
			});
		}
	}).on("mousedown", ".coll-tri", function(e) {
		if (e.button > 0) return;
		e.preventDefault();  // do not start selection
		var $tri = $(this), $coll = $tri.closest(".collection");
		$coll.addClass("with-menu");
		$collMenu.hide().removeClass('showing').appendTo($coll)
			.toggleClass("page-bottom", $coll[0].getBoundingClientRect().bottom > $(window).height() - 70)
			.show().layout().addClass('showing')
			.data("docMouseDown", docMouseDown);
		document.addEventListener("mousedown", docMouseDown, true);
		function docMouseDown(e) {
			if (!e.button && !$.contains($collMenu[0], e.target)) {
				hideCollMenu();
				if ($(e.target).hasClass("coll-tri")) {
					e.stopPropagation();
				}
			}
		}
	});
	var collScroller = $collList.data("antiscroll");
	$(window).resize(collScroller.refresh.bind(collScroller));

	$colls.on("click", "h3.collection>a", function(e) {
		if (e.target === this && $(this.parentNode).hasClass("renaming")) {
			e.preventDefault();
			$(this).find("input").focus();
		}
	})
	.on("click", ".collection-create", function() {
		clearTimeout(hideNewCollTimeout), hideNewCollTimeout = null;
		if (!$newColl.is(":animated")) {
			if ($newColl.is(":visible")) {
				$newColl.slideUp(80).find("input").val("").prop("disabled", true);
			} else {
				$newColl.slideDown(80, function() {
					$collList.find(".antiscroll-inner")[0].scrollTop = 0;
					$newColl.find("input").prop("disabled", false).focus().select();
				});
			}
		}
	});

	var $newColl = $colls.find(".collection-new"), hideNewCollTimeout;
	$newColl.find("input").on("blur keydown", function(e) {
		if ((e.which === 13 || e.type === "blur") && !$newColl.is(":animated")) { // 13 is Enter
			var name = $.trim(this.value);
			if (name) {
				createCollection(name, function(collId) {
					$newColl.removeClass("submitted");
					if (collId) {
						$newColl.hide().find("input").val("").prop("disabled", true);
					}
				});
			} else if (e.type === "blur") {
				if ($newColl.is(":visible"))
				// avoid back-to-back hide/show animations if "create collection" clicked again
				hideNewCollTimeout = setTimeout(hide.bind(this), 300);
			} else {
				e.preventDefault();
				hide.call(this);
			}
		} else if (e.which === 27 && !$newColl.is(":animated")) { // 27 is Esc
			hide.call(this);
		}
		function hide() {
			this.value = "";
			this.disabled = true;
			this.blur();
			$newColl.slideUp(200);
			clearTimeout(hideNewCollTimeout), hideNewCollTimeout = null;
		}
	}).focus(function() {
		clearTimeout(hideNewCollTimeout), hideNewCollTimeout = null;
	});

	function createCollection(name, callback) {
		$newColl.addClass("submitted");
		$.postJson(xhrBase + '/collections/create', {name: name}, function(data) {
			collTmpl.prepend(collections[data.id] = {id: data.id, name: name, keeps: 0});
			callback(data.id);
		}).error(function() {
			showMessage('Could not create collection, please try again later');
			callback();
		});
	}

	function addKeepsToCollection(collId, $keeps, onError) {
		$.postJson(xhrBase + '/keeps/add', {
				collectionId: collId,
				keeps: $keeps.map(function() {var a = this.querySelector(".keep-title>a"); return {title: a.title, url: a.href}}).get()},
			function(data) {
				$collList.find(".collection[data-id=" + collId + "]").find(".nav-count").text(collections[collId].keeps += data.addedToCollection);
				var collName = collections[collId].name;
				$keeps.addClass("mine")
					.find(".keep-colls:not(:has(.keep-coll[data-id=" + collId + "]))")
					.contents().filter(function() {return this.nodeType == 3}).remove().end().end()
					.append('<span class=keep-coll data-id=' + collId + '>' +
						'<a class="keep-coll-a" href="javascript:">' + collName + '</a><a class="keep-coll-x" href="javascript:"></a>' +
						'</span>');
				if ($keeps.is(".detailed")) {
					$detail.children().attr("data-kept", $keeps.has(".keep-private.on").length == $keeps.length ? "pri" : "pub");
					var $inColl = $detail.find(".page-coll-list");
					if (!$inColl.has(".page-coll[data-id=" + collId + "]").length) {
						inCollTmpl.into($inColl[0]).append({id: collId, name: collName});
					}
				}
			}).error(function() {
				showMessage('Could not add to collection, please try again later');
				if (onError) onError();
			});
	}

	function removeFromCollection(collId, $keeps) {
		$.postJson(xhrBase + '/collections/' + collId + '/removeKeeps', $keeps.map(getDataId).get(), function(data) {
			if ($collList.find('.collection.active').data('id') === collId) {
				var $titles = obliviate($keeps);
				hideKeepDetails();
				showUndo(
					($keeps.length > 1 ? $keeps.length + ' Keeps' : 'Keep') + ' removed from this collection.',
					undoRemoveFromCollection.bind(null, collId, $keeps, $titles),
					$.fn.remove.bind($keeps));
			} else {
				var $keepColl = $keeps.find(".keep-coll[data-id=" + collId + "]");
				$keepColl.css("width", $keepColl[0].offsetWidth).layout().on("transitionend", removeIfThis).addClass("removed");
				var $pageColl = $detail.find(".page-coll[data-id=" + collId + "]");
				$pageColl.css("width", $pageColl[0].offsetWidth).layout().on("transitionend", removeIfThis).addClass("removed");
			}
			$collList.find(".collection[data-id=" + collId + "]").find(".nav-count").text(collections[collId].keeps -= data.removed);
		}).error(showMessage.bind(null, 'Could not remove keep' + ($keeps.length > 1 ? 's' : '') + ' from collection, please try again later'));
	}

	function undoRemoveFromCollection(collId, $keeps, $titles) {
		$.postJson(xhrBase + '/collections/' + collId + '/addKeeps', $keeps.map(getDataId).get(), function(data) {
			rennervate($keeps, $titles);
			updateKeepDetails();
			$collList.find(".collection[data-id=" + collId + "]").find(".nav-count").text(collections[collId].keeps += data.added);
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
	$detail.on('click', '.page-x', function() {
		$main.find('.keep.detailed').removeClass('detailed');
		hideKeepDetails();
	})
	.on("click", '.page-keep,.page-priv', function(e) {
		var $keeps = $main.find(".keep.detailed");
		var $a = $(this), howKept = $detail.children().attr("data-kept");
		if (!howKept) {  // keep
			howKept = $a.hasClass('page-keep') ? "pub" : "pri";
			$.postJson(xhrBase + '/keeps/add', {
					keeps: $keeps.map(function() {
						var a = $(this).find('.keep-title>a')[0];
						return {title: a.title, url: a.href, isPrivate: howKept == 'pri'};
					}).get()
				}, function(data) {
					$detail.children().attr('data-kept', howKept).find('.page-how').attr('class', 'page-how ' + howKept);
					$keeps.addClass("mine").find(".keep-private").toggleClass("on", howKept == "pri");
				}).error(showMessage.bind(null, 'Could not add Keeps, please try again later'));
		} else if ($a.hasClass('page-keep')) {  // unkeep
			$.postJson(xhrBase + '/keeps/remove', $keeps.map(function() {return {url: this.querySelector('.keep-title>a').href}}).get(), function(data) {
				// TODO: update number in "Showing top 30 results" tagline? load more instantly if number gets too small?
				var collCounts = $keeps.find(".keep-coll").map(getDataId).get()
					.reduce(function(o, id) {o[id] = (o[id] || 0) + 1; return o}, {});
				for (var collId in collCounts) {
					$collList.find(".collection[data-id=" + collId + "]").find(".nav-count").text(collections[collId].keeps -= collCounts[collId]);
				}
				var $keepsStaying = searchResponse ? $keeps.has('.keep-friends,.keep-others') : $();
				$keepsStaying.each(function() {
					var $keep = $(this), $priv = $keep.find('.keep-private');
					$keep.data({
						sel: $keep.hasClass('selected'),
						priv: $priv.hasClass('on'),
						$coll: $keep.find('.keep-coll')})
					.removeClass('mine selected detailed');
					$priv.removeClass('on');
				});
				var $keepsGoing = $keeps.not($keepsStaying);
				var $titles = obliviate($keepsGoing);
				hideKeepDetails();
				showUndo(
					$keeps.length > 1 ? $keeps.length + ' Keeps deleted.' : 'Keep deleted.',
					undoUnkeep.bind(null, $keeps, $titles),
					$.fn.remove.bind($keepsGoing));
			}).error(showMessage.bind(null, 'Could not delete keeps, please try again later'));
		} else {  // toggle public/private
			howKept = howKept == "pub" ? "pri" : "pub";
			$detail.children().attr('data-kept', howKept).find('.page-how').attr('class', 'page-how ' + howKept);
			$keeps.each(function() {
				var $keep = $(this), keepLink = $keep.find('.keep-title>a')[0];
				// TODO: support bulk operation with one server request
				$.postJson(
					xhrBase + '/keeps/add',
					{keeps: [{title: keepLink.title, url: keepLink.href, isPrivate: howKept == 'pri'}]},
					function() {
						$keep.find('.keep-private').toggleClass('on', howKept == 'pri');
					}).error(showMessage.bind(null, 'Could not update keep, please try again later'));
			});
		}
	}).on("click", ".page-coll-x", function(e) {
		e.preventDefault();
		removeFromCollection($(this.parentNode).data("id"), $main.find(".keep.detailed"));
	}).on("click", ".page-coll-add", function() {
		var $btn = $(this), $in = $(".page-coll-input").css("width", $btn.outerWidth());
		$btn.hide();
		$in.add(".page-coll-sizer").show();
		$in.layout().css("width", "").prop("disabled", false).focus().select().trigger("input");
	}).on("blur", ".page-coll-input", function(e) {
		var input = this;
		hideAddCollTimeout = setTimeout(hide, 50);
		function hide() {
			clearTimeout(hideAddCollTimeout), hideAddCollTimeout = null;
			var $btn = $(".page-coll-add").css({display: "", visibility: "hidden", position: "absolute"}), width = $btn.outerWidth();
			$btn.css({display: "none", visibility: "", position: ""});
			var $in = $(input).val("").on("transitionend", function end() {
				$in.off("transitionend", end).prop("disabled", true).add(".page-coll-sizer").hide().css("width", "");
				$btn.show();
			}).layout().css("width", width);
			$('.page-coll-opts').slideUp(120, function() {
				$(this).empty();
			});
		}
	}).on("focus", ".page-coll-input", function(e) {
		clearTimeout(hideAddCollTimeout), hideAddCollTimeout = null;
	}).on("keydown", ".page-coll-input", function(e) {
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
				var $old = $('.page-coll-opt.current'), $new = $old[e.which == 38 ? 'prev' : 'next']('.page-coll-opt');
				if ($new.length) {
					$old.removeClass('current');
					$new.addClass('current');
				}
				break;
		}
	}).on("input", ".page-coll-input", function(e) {
		var width = $(this.previousElementSibling).text(this.value).outerWidth();
		$(this).css("width", Math.min(Math.max(100, width) + 34, $('.page-colls').outerWidth()));
		var allColls = $.map(collections, identity), colls;
		var val = $.trim(this.value);
		if (val) {
			var re = val.split(/\s+/).map(function(p) {return new RegExp('\\b' + p, 'i')});
			var scores = {};
			colls = allColls.filter(function(c) {
				var arr = re.map(function(re) {return re.exec(c.name)});
				if (arr.every(identity)) {
					scores[c.id] = arr.reduce(function(score, m) {
						score.min = Math.min(score.min, m.index);
						score.sum += m.index;
						return score;
					}, {min: Infinity, sum: 0});
					return true;
				}
			}).sort(function(c1, c2) {
				var s1 = scores[c1.id];
				var s2 = scores[c2.id];
				return (s1.min - s2.min) || (s1.sum - s2.sum) || c1.name.localeCompare(c2.name, undefined, compareSort);
			}).splice(0, 4).map(function(c) {
				for (var name = escapeHTMLContent(c.name), i = re.length; i--;) {
					name = name.replace(new RegExp("^((?:[^&<]|&[^;]*;|<[^>]*>)*)\\b(" + re[i].source + ")", "gi"), "$1<b>$2</b>");
				}
				return {id: c.id, name: name};
			});
			if (!allColls.some(function(c) {return c.name.localeCompare(val, undefined, compareSearch) === 0})) {
				colls.push({id: "", name: val});
			}
		} else {
			colls = allColls.sort(function(c1, c2) {
				return c2.keeps - c1.keeps || c1.name.localeCompare(c2.name, undefined, compareSort);
			}).splice(0, 4).map(function(c) {
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
	}).on("mousemove", ".page-coll-opt", function() {
		if (this.className.indexOf("current") < 0) {
			$(this).siblings(".current").removeClass("current").end().addClass("current");
		}
	}).on("mousedown", ".page-coll-opt", function(e) {
		e.preventDefault();  // selection start
		if (e.which > 1) return;
		var collId = $(this).data('id'), $in = $('.page-coll-input');
		if (collId) {
			withCollId(collId);
		} else {
			createCollection($.trim($in.val()), withCollId);
		}
		function withCollId(collId) {
			if (collId) {
				$in.val("").trigger("input")	;
				addKeepsToCollection(collId, $main.find(".keep.detailed"));
			}
		}
	});

	// Removes keeps from current view using animation, detaches them from DOM, and remembers their positions.
	// Also removes (and returns) any keep group titles that no longer have any keeps beneath them.
	function obliviate($keeps) {
		$keeps.each(function() {
			$(this).each(notePosition).css('height', this.offsetHeight);
		}).layout().addClass('toggling').on('transitionend', detachAndOff).addClass('unkept');
		return searchResponse ? $() :
			$myKeeps.find('.keep-group-title').filter(function() {
				for (var $li = $(this); ($li = $li.next()).hasClass('keep');) {
					if (!$li.hasClass('detailed')) return;
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
		$keeps.each(reattach).layout().one('transitionend', function() {
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
				keeps: $keeps.map(function() {
					var a = this.querySelector('.keep-title>a'), data = $(this).data();
					return {title: a.title, url: a.href, isPrivate: 'priv' in data ? data.priv : !!this.querySelector('.keep-private.on')};
				}).get()
			}, function(data) {
				var $keepsRemoved = $keeps.filter(function() {return 'prev' in $(this).data()});
				rennervate($keepsRemoved, $titles);
				$keeps.not($keepsRemoved).each(function() {
					var $k = $(this), data = $k.data();
					$k.addClass('mine detailed').toggleClass('selected', data.sel);
					$k.find('.keep-private').toggleClass('on', data.priv);
					$k.find('.keep-colls').append(data.$coll);
				}).removeData('sel priv $coll');
				updateKeepDetails();
				var collCounts = $keeps.find('.keep-coll').map(getDataId).get()
					.reduce(function(o, id) {o[id] = (o[id] || 0) + 1; return o}, {});
				for (var collId in collCounts) {
					$collList.find('.collection[data-id=' + collId + ']').find('.nav-count').text(collections[collId].keeps += collCounts[collId]);
				}
			});
	}

	var $undo = $(".undo").on('click', '.undo-link', function() {
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
			clearTimeout(d.timeout), delete d.timeout;
			if (duration) {
				$undo.fadeOut(duration, expireUndo);
			} else {
				$undo.hide(), expireUndo();
			}
		}
	}
	function expireUndo() {
		var d = $undo.data();
		d.commit && d.commit();
		delete d.undo, delete d.commit;
	}

	var $sendFeedback = $(".send-feedback").click(function() {
		if (!window.UserVoice) {
			window.UserVoice = [];
			$.getScript("//widget.uservoice.com/2g5fkHnTzmxUgCEwjVY13g.js");
		}
		UserVoice.push(['showLightbox', 'classic_widget', {
			mode: 'full',
			primary_color: '#cc6d00',
			link_color: '#007dbf',
			default_mode: 'support',
			forum_id: 200379,
			custom_template_id: 3305}]);
	}).filter('.top-right-nav>*');

	function updateMe(data) {
		me = data;
		$(".my-pic").css("background-image", "url(" + formatPicUrl(data.id, data.pictureName, 200) + ")");
		$(".my-name").text(data.firstName + ' ' + data.lastName);
		$(".my-description").text(data.description || '\u00A0'); // nbsp
		$friendsTabs.filter('[data-href="friends/invite"]').toggle(canInvite());
	}

	function canInvite() {
		return me.experiments.indexOf('admin') >= 0 ||
			me.experiments.indexOf('can invite') >= 0;
	}

	function updateFriendRequests(n) {
		var $a = $('h3.my-friends>a'), $count = $a.find('.nav-count');
		if (n < 0) {
			n += +$count.text();
		}
		$count.add('.friend-req-count').text(n || '');
		$a[0].href = n ? 'friends/requests' : 'friends';
	}

	// load data for persistent (view-independent) page UI
	var promise = {
		me: $.getJSON(xhrBase + '/user/me', updateMe).promise(),
		myNetworks: $.getJSON(xhrBase + '/user/networks', function(data) {
			myNetworks = data;
		}).promise(),
		myPrefs: $.getJSON(xhrBase + '/user/prefs', function(data) {
			myPrefs = data;
			if (myPrefs.site_left_col_width) {
				$(".left-col").animate({width: +myPrefs.site_left_col_width}, 120);
			}
		}).promise()};
	updateCollections();
	updateNumKeeps();
	$.getJSON(xhrBase + '/user/friends/count', function(data) {
		updateFriendRequests(data.requests);
	});

	// render initial view
	$(window).trigger('statechange');

	// auto-update my keeps
	setTimeout(function refresh() {
		updateNumKeeps();
		addNewKeeps();
		setTimeout(refresh, 25000 + 5000 * Math.random());
	}, 30000);

	var $welcomeDialog = $('.welcome-dialog').remove().show();
	$.when(promise.myPrefs).done(function() {
		if (!myPrefs.site_welcomed) {
			$welcomeDialog.dialog('show').on('click', 'button', function() {
				$welcomeDialog.dialog('hide');
				$welcomeDialog = null;
				setTimeout($.fn.hoverfu.bind($sendFeedback, 'show'), 1000);
			}).find('button').focus();
			$.postJson(xhrBase + '/user/prefs', {site_welcomed: 'true'}, function(data) {
				console.log("[prefs]", data);
			});
		} else {
			$welcomeDialog = null;
		}
	});

	// bind hover behavior later to avoid slowing down page load
	var friendCardTmpl = Tempo.prepare('fr-card-template'); $('#fr-card-template').remove();
	$.getScript('js/jquery-hoverfu.min.js').done(function() {
		$sendFeedback.hoverfu(function(configure) {
			configure({
				position: {my: "center-24 bottom-12", at: "center top", of: this},
				mustHoverFor: 400,
				hideAfter: 1800,
				click: 'hide'});
		});
		$(document).hoverfu(".pic:not(.me)", function(configureHover) {
			var $a = $(this), id = $a.data('id'), $temp = $('<div>');
			friendCardTmpl.into($temp).append({
				name: $a.data('name'),
				picUri: formatPicUrl(id, $a.css('background-image').match(/\/([^\/]*)['"]?\)$/)[1], 200)});
			var $el = $temp.children().detach();
			configureHover($el, {
				position: {my: "left-33 bottom-13", at: "center top", of: $a, collision: "flipfit flip", using: show},
				mustHoverFor: 400,
				canLeaveFor: 600,
				hideAfter: 4000,
				click: "toggle"});
			$.getJSON(xhrBase + '/user/' + id + '/networks', function(networks) {
				for (nw in networks) {
					console.log("[networks]", nw, networks[nw]);
					$el.find('.friend-nw-' + nw)
						.attr('href', networks[nw].connected || null);
				}
			});
		});
		function show(pos, o) {
			o.element.element.css(pos).addClass(o.horizontal + ' ' + o.vertical)
				.find(".fr-card-tri").css('left', Math.round(o.target.left - o.element.left + .5 * o.target.width));
		}
	});
});
