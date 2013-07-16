var urlBase = 'https://api.kifi.com';
//var urlBase = 'http://dev.ezkeep.com:9000';
var urlSite = urlBase + '/site';
var urlSearch = urlBase + '/search';
var urlKeeps =  urlSite + '/keeps';
var urlKeepAdd =  urlKeeps + '/add';
var urlKeepRemove =  urlKeeps + '/remove';
var urlMyKeeps = urlKeeps + '/all';
var urlMyKeepsCount = urlKeeps + '/count';
var urlUser = urlSite + '/user';
var urlMe = urlUser + '/me';
var urlMyPrefs = urlUser + '/prefs';
var urlChatter = urlSite + '/chatter';
var urlCollections = urlSite + '/collections';
var urlCollectionsAll = urlCollections + '/all';
var urlCollectionsOrder = urlCollections + '/ordering';
var urlCollectionsCreate = urlCollections + '/create';
var urlScreenshot = urlKeeps + '/screenshot';

$.ajaxSetup({
	xhrFields: {withCredentials: true},
	crossDomain: true});

$.timeago.settings.localeTitle = true;
$.extend($.timeago.settings.strings, {
	seconds: "seconds",
	minute: "a minute",
	hour: "an hour",
	hours: "%d hours",
	month: "a month",
	year: "a year"});

$.fn.layout = function() {
	return this.each(function() {this.clientHeight});  // forces layout
};

$.fn.removeText = function() {
	return this.contents().filter(function() {return this.nodeType == 3}).remove().end().end();
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
			$.ajax({
				url: urlMyPrefs,
				type: "POST",
				dataType: 'json',
				data: JSON.stringify({site_left_col_width: String($leftCol.outerWidth())}),
				contentType: 'application/json',
				done: function(data) {
					console.log("[prefs]", data);
				}});
		}
	});
	$leftCol.find(".ui-resizable-handle").appendTo($leftCol.find(".page-col-inner"));

	var $subtitle = $(".subtitle"), subtitleTmpl = Tempo.prepare($subtitle);
	var $checkAll = $subtitle.find('.check-all').click(function() {
		if ($checkAll.hasClass('live')) {
			var $keeps = $main.find('.keep');
			$checkAll.toggleClass('checked');
			if ($checkAll.hasClass('checked')) {
				$keeps.addClass('selected detailed');
			} else {
				$keeps.filter('.selected').removeClass('selected detailed');
			}
			updateDetails();
		}
	}).hover(function() {
		var $text = $checkAll.next('.subtitle-text');
		var n = $text.data("n");
		if (n) {
			$checkAll.addClass("live");
			$text.data("text", $text.text()).text(
				n == 1 ? "the keep below" :
				 n == 2 ? "both keeps below" :
				 "all " + n + " keeps below");
		}
	}, function() {
		var $text = $(this).removeClass("live").next('.subtitle-text');
		var text = $text.data("text");
		if (text) {
			$text.text(text).removeData("text");
		}
	});

	$('.keep-colls,.keep-coll').removeText();
	var $myKeeps = $("#my-keeps"), $results = $("#search-results"), keepsTmpl = Tempo.prepare($myKeeps).when(TempoEvent.Types.RENDER_COMPLETE, function(ev) {
		$keepSpinner.hide();

		var now = new Date;
		$(ev.element).find("time").timeago().each(function() {
			var age = daysBetween(new Date($(this).attr("datetime")), now);
			if ($myKeeps.find('li.keep-group-title.today').length == 0 && age <= 1) {
				$(this).closest(".keep").before('<li class="keep-group-title today">Today</li>');
			} else if ($myKeeps.find('li.keep-group-title.yesterday').length == 0 && age > 1 && age < 2) {
				$(this).closest(".keep").before('<li class="keep-group-title yesterday">Yesderday</li>');
			} else if ($myKeeps.find('li.keep-group-title.week').length == 0 && age >= 2 && age <= 7) {
				$(this).closest(".keep").before('<li class="keep-group-title week">Past Week</li>');
			} else if ($myKeeps.find('li.keep-group-title.older').length == 0 && age > 7) {
				$(this).closest(".keep").before('<li class="keep-group-title older">Older</li>');
				return false;
			}
		});

		$(ev.element).find(".keep").draggable(draggableKeepOpts);
		mainScroller.refresh();
	});
	var draggableKeepOpts = {
		revert: "invalid",
		handle: ".handle",
		cancel: ".keep-checkbox",
		appendTo: "body",
		cursorAt: { top: 15, left: 0 },
		helper: function() {
			var $keep = $(this), $sel = $keep.hasClass("selected") ? $keep.parent().find(".keep.selected") : $keep;
			return $('<div>', {
				"class": "drag-helper",
				"text": $sel.length > 1 ? $sel.length + " selected keeps" : $keep.find(".keep-title>a").text()});
		}};

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
	var myPrefs;
	var myKeepsCount;
	var collections;
	var searchResponse;
	var searchTimeout;
	var lastKeep;

	function identity(a) {
		return a;
	}

	function daysBetween(date1, date2) {
		return Math.round((date2 - date1) / 86400000);  // ms in one day
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

	function showDetails() {
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
	function hideDetails() {
		var d = $(window).width() - $main[0].getBoundingClientRect().right;
		$detail.on("transitionend", function end(e) {
			if (e.target === this) {
				$(this).off("transitionend").css({display: "", transform: ""});
			}
		}).css({transform: "translate(" + d + "px,0)", "transition-timing-function": "ease-in"});
	}
	function updateDetails() {
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
				$('.page-who-pics').append($detailed.find(".keep-who>img").clone());
				$('.page-who-text').html($detailed.find(".keep-who-text").html());
				var $pic = $('.page-pic'), $chatter = $('.page-chatter');
				$.ajax({
					url: urlScreenshot,
					type: 'POST',
					dataType: 'json',
					data: JSON.stringify({url: o.url}),
					contentType: 'application/json',
					success: function(data) {
						$pic.css('background-image', 'url(' + data.url + ')');
					},
					error: function() {
						$pic.find('.page-pic-soon').show();
					}});
				$.ajax({
					url: urlChatter,
					type: 'POST',
					dataType: 'json',
					data: JSON.stringify({url: o.url}),
					contentType: 'application/json',
					success: function(data) {
						$chatter.find('.page-chatter-messages').attr('data-n', data.conversations || 0);
						$chatter.find('.page-chatter-comments').attr('data-n', data.comments || 0);
					}});
			} else { // multiple keeps
				var collCounts = collIds.reduce(function(o, id) {o[id] = (o[id] || 0) + 1; return o}, {});
				o.collections = collIds.sort(function(id1, id2) {return collCounts[id1] - collCounts[id2]}).map(collIdAndName);
				detailTmpl.render(o);
			}
			inCollTmpl.into($detail.find('.page-coll-list')[0]).render(o.collections);
			showDetails();
		} else {
			hideDetails();
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
		$main.attr("data-view", "search");
		if (!searchResponse) {
			subtitleTmpl.render({searching: true});
			$checkAll.removeClass('live checked');
			$myKeeps.detach().find('.keep').removeClass('selected detailed');
		}
		$keepSpinner.show();
		$loadMore.addClass('hidden');

		$query.attr("data-q", q);
		var context = searchResponse && searchResponse.context;
		$.getJSON(urlSearch, {q: q, f: "a", maxHits: 30, context: context}, function(data) {
			$.when(promise.me).done(function() {
				searchResponse = data;
				var numShown = data.hits.length + (context ? $results.find(".keep").length : 0);
				subtitleTmpl.render({numShown: numShown, query: data.query});
				if (numShown) $checkAll.addClass('live');
				data.hits.forEach(prepHitForRender);
				if (context) {
					keepsTmpl.into($results[0]).append(data.hits);
				} else {
					keepsTmpl.into($results[0]).render(data.hits);
					hideDetails();
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
			var id = colls[i];
			colls[i] = {id: id, name: collections[id].name};
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
		$.getJSON(urlMyKeeps, params,
			function(data) {
				var keepIds = $myKeeps.find('.keep').map(getDataId).get().reduce(function(ids, id) {ids[id] = true; return ids}, {});
				var keeps = data.keeps.filter(function(k) {return !keepIds[k.id]});
				keeps.forEach(prepKeepForRender);
				keepsTmpl.into($myKeeps[0]).prepend(keeps);
				// TODO: insert this group heading if not already there
				$myKeeps.find('.keep-group-title.today').prependTo($myKeeps);
			});
	}

	function showMyKeeps(collId) {
		collId = collId || null;
		var $h3 = $(".left-col h3");
		$h3.filter(".active").removeClass("active");
		$h3.filter(collId ? "[data-id='" + collId + "']" : ".my-keeps").addClass("active");

		var fromSearch = $main.attr("data-view") == "search";
		$main.attr("data-view", "mine")
			.find("h1").text(collId ? collections[collId].name : "Browse your keeps");

		$results.empty();
		$query.val("").removeAttr("data-q");
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
			hideDetails();
			loadKeeps(collId);
		} else {
			var numShown = $myKeeps.find(".keep").length;
			subtitleTmpl.render({
				numShown: numShown,
				numTotal: collId ? collections[collId].keeps : myKeepsCount,
				collId: collId || undefined});
			$checkAll.toggleClass('live', numShown > 0).removeClass('checked');
			if (fromSearch) {
				hideDetails();
			}
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
			$.getJSON(urlMyKeeps, params, function withKeeps(data) {
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
		$.getJSON(urlMyKeepsCount, function(data) {
			$('.left-col .my-keeps .keep-count').text(myKeepsCount = data.numKeeps);
		});
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
		if (e.which > 1) return;
		hideCollMenu();
		var $coll = $collMenu.closest(".collection");
		var collId = $coll.data("id");
		console.log("Removing collection", collId);
		$.ajax({
			url: urlCollections + "/" + collId + "/delete",
			type: "POST",
			dataType: 'json',
			data: '{}',
			contentType: 'application/json',
			error: showMessage.bind(null, 'Could not delete collection, please try again later'),
			success: function(data) {
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
			}});
	}).on("mouseup mousedown", ".coll-rename", function(e) {
		if (e.which > 1) return;
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
					$.ajax({
						url: urlCollections + "/" + collId + "/update",
						type: "POST",
						dataType: 'json',
						data: JSON.stringify({name: newName}),
						contentType: 'application/json',
						error: function() {
							showMessage('Could not rename collection, please try again later');
							$name.text(oldName);
						},
						success: function() {
							collections[collId].name = newName;
							if ($myKeeps.data("collId") === collId) {
								$main.find("h1").text(newName);
							}
						}
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
		$collMenu.removeData("docMouseDown").slideUp(80, function() {
			$collMenu.detach().find(".hover").removeClass("hover");
		}).closest(".collection").removeClass("with-menu");
	}

	$(document).keydown(function(e) {  // auto focus on search field when starting to type anywhere on the document
		if (!$(e.target).is('input,textarea') && e.which >= 48 && e.which <= 90 && !e.ctrlKey && !e.metaKey && !e.altKey) {
			$query.focus();
		}
	});

	$(window).on('statechange anchorchange', function(e) {
		console.log('[' + e.type + ']', location.href);
		var parts = location.pathname.substring($('base').attr('href').length).split('/');
		switch (parts[0]) {
			case '':
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
				doSearch(decodeURIComponent(queryFromQS(location.search)));
		}
	});

	function navigate(uri) {
		var baseUri = document.baseURI;
		if (uri.substr(0, baseUri.length) == baseUri) {
			uri = uri.substr(baseUri.length);
		}
		var title, kind = uri.match(/[\w]*/)[0];
		switch (kind) {
			case '':
				title = 'Your Keeps';
				break;
			case 'collection':
				title = collections[uri.substr(kind.length + 1)].name;
				break;
			case 'search':
				title = queryFromQS(uri.substr(kind.length));
		}
		History.pushState(null, 'kifi.com • ' + title, uri);
	}

	function queryFromQS(qs) {
		return qs.replace(/.*?[?&]q=([^&]*).*/, '$1').replace(/\+/g, ' ');
	}

	var $main = $(".main").on("mousedown", ".keep-checkbox", function(e) {
		e.preventDefault();  // avoid starting selection
	}).on("click", ".keep-coll-a", function(e) {
		e.stopPropagation(), e.preventDefault();
		navigate(this.href);
	}).on("click", ".keep-coll-x", function(e) {
		e.stopPropagation(), e.preventDefault();
		var $coll = $(this.parentNode);
		removeKeepsFromCollection($coll.data("id"), [$coll.closest(".keep").data("id")]);
	}).on("click", ".keep-title>a", function(e) {
		e.stopPropagation();
	}).on("click", ".keep", function(e) {
		var $keep = $(this), $keeps = $main.find(".keep");
		if ($(e.target).hasClass("keep-checkbox") || $(e.target).hasClass("handle")) {
			$keep.toggleClass("selected");
			var $selected = $keeps.filter(".selected");
			$checkAll.toggleClass("checked", $selected.length == $keeps.length);
			if ($selected.length == 0 ||
				  $selected.not(".detailed").addClass("detailed").length +
			    $keeps.filter(".detailed:not(.selected)").removeClass("detailed").length == 0) {
				return;  // avoid redrawing same details
			}
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
		updateDetails();
	});
	var $mainHead = $(".main-head");
	var $mainKeeps = $(".main-keeps").antiscroll({x: false, width: "100%"});
	$mainKeeps.find(".antiscroll-inner").scroll(function() { // infinite scroll
		var sT = this.scrollTop;
		$mainHead.toggleClass("scrolled", sT > 0);
		if ($keepSpinner.css('display') == 'none' && this.clientHeight + sT > this.scrollHeight - 300) {
			if ($main[0].querySelector('.keep.selected')) {
				$loadMore.removeClass('hidden');
			} else {
				$loadMore.triggerHandler('click');
			}
		}
	});
	var mainScroller = $mainKeeps.data("antiscroll");
	$(window).resize(mainScroller.refresh.bind(mainScroller));

	var splashScroller = $(".splash").antiscroll({x: false, width: "100%"}).data("antiscroll");
	$(window).resize(splashScroller.refresh.bind(splashScroller));

	var $query = $("input.query").on("keydown input", function(e) {
		console.log("[clearTimeout]", e.type);
		clearTimeout(searchTimeout);
		var q = $.trim(this.value);
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

	var $collList = $("#collections-list")
	.each(function() {this.style.top = this.offsetTop + "px"})
	.addClass("positioned")
	.antiscroll({x: false, width: "100%"})
	.sortable({
		axis: "y",
		items: ".collection",
		cancel: ".coll-tri,#coll-menu,.renaming",
		opacity: .6,
		placeholder: "sortable-placeholder",
		beforeStop: function(event, ui) {
			// update the collection order
			$.ajax({
				url: urlCollectionsOrder,
				type: "POST",
				async: false,
				dataType: 'json',
				data: JSON.stringify($(this).find(".collection").map(getDataId).get()),
				contentType: 'application/json',
				error: showMessage.bind(null, 'Could not reorder the collections, please try again later'),
				success: function(data) {
					console.log(data);
				}});
		}
	}).on("mousedown", ".coll-tri", function(e) {
		if (e.button > 0) return;
		e.preventDefault();  // do not start selection
		if ($collMenu.is(":animated")) return;
		var $tri = $(this), $coll = $tri.closest(".collection").addClass("with-menu");
		$collMenu.hide().appendTo($coll)
			.toggleClass("page-bottom", $coll[0].getBoundingClientRect().bottom > $(window).height() - 51)
			.slideDown(80)
			.data("docMouseDown", docMouseDown);
		document.addEventListener("mousedown", docMouseDown, true);
		function docMouseDown(e) {
			if (!e.button && !$.contains($collMenu[0], e.target)) {
				hideCollMenu();
			}
		}
	});
	var collScroller = $collList.data("antiscroll");
	$(window).resize(collScroller.refresh.bind(collScroller));

	$(".left-col .my-keeps>a").click(function(e) {
		e.preventDefault();
		navigate(this.href);
	});

	$colls.on("click", "h3.collection>a", function(e) {
		e.preventDefault();
		var $a = $(this), $coll = $a.parent();
		if ($coll.hasClass("renaming")) {
			if (e.target === this) {
				$a.find("input").focus();
			}
		} else {
			navigate(this.href);
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
		$.ajax({
			url: urlCollectionsCreate,
			type: "POST",
			dataType: 'json',
			data: JSON.stringify({name: name}),
			contentType: 'application/json',
			error: function() {
				showMessage('Could not create collection, please try again later');
				callback();
			},
			success: function(data) {
				collTmpl.prepend(collections[data.id] = {id: data.id, name: name, keeps: 0});
				callback(data.id);
			}});
	}

	function addKeepsToCollection(collId, $keeps, onError) {
		$.ajax({
			url: urlKeepAdd,
			type: "POST",
			dataType: 'json',
			data: JSON.stringify({
				collectionId: collId,
				keeps: $keeps.map(function() {var a = this.querySelector(".keep-title>a"); return {title: a.title, url: a.href}}).get()}),
			contentType: 'application/json',
			error: function() {
				showMessage('Could not add to collection, please try again later');
				if (onError) onError();
			},
			success: function(data) {
				$collList.find(".collection[data-id=" + collId + "]").find(".keep-count").text(collections[collId].keeps += data.addedToCollection);
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
			}
		});
	}

	function removeKeepsFromCollection(collId, keepIds) {
		$.ajax({
			url: urlCollections + "/" + collId + "/removeKeeps",
			type: "POST",
			dataType: 'json',
			data: JSON.stringify(keepIds),
			contentType: 'application/json',
			error: showMessage.bind(null, 'Could not remove keep' + (keepIds.length > 1 ? 's' : '') + ' from collection, please try again later'),
			success: function(data) {
				$collList.find(".collection[data-id=" + collId + "]").find(".keep-count").text(collections[collId].keeps -= data.removed);
			}});
		var $allKeeps = $main.find(".keep");
		var $keeps = $allKeeps.filter(function() {return keepIds.indexOf($(this).data("id")) >= 0});
		var $keepColl = $keeps.find(".keep-coll[data-id=" + collId + "]");
		$keepColl.css("width", $keepColl[0].offsetWidth).layout().on("transitionend", removeIfThis).addClass("removed");
		if ($keeps.is(".detailed") && !$allKeeps.filter(".detailed").not($keeps).has(".keep-coll[data-id=" + collId + "]").length) {
			// there are no other selected keeps in the collection, so must remove collection from detail pane
			var $pageColl = $detail.find(".page-coll[data-id=" + collId + "]");
			$pageColl.css("width", $pageColl[0].offsetWidth).layout().on("transitionend", removeIfThis).addClass("removed");
		}
	}

	function removeIfThis(e) {
		if (e.target === this) {
			$(this).remove();
		}
	}

	// keep / unkeep
	var hideAddCollTimeout;
	$detail.on("click", '.page-keep,.page-priv', function(e) {
		var $keeps = $main.find(".keep.detailed");
		var $a = $(this), howKept = $detail.children().attr("data-kept");
		if (!howKept) {  // keep
			howKept = $a.hasClass('page-keep') ? "pub" : "pri";
			$.ajax({
				url: urlKeepAdd,
				type: "POST",
				dataType: 'json',
				data: JSON.stringify({
					keeps: $keeps.map(function() {
						var a = $(this).find('.keep-title>a')[0];
						return {title: a.title, url: a.href, isPrivate: howKept == 'pri'};
					}).get()}),
				contentType: 'application/json',
				error: showMessage.bind(null, 'Could not add keeps, please try again later'),
				success: function(data) {
					$detail.children().attr('data-kept', howKept).find('.page-how').attr('class', 'page-how ' + howKept);
					$keeps.addClass("mine").find(".keep-private").toggleClass("on", howKept == "pri");
				}});
		} else if ($a.hasClass('page-keep')) {  // unkeep
			$.ajax({
				url: urlKeepRemove,
				type: "POST",
				dataType: 'json',
				data: JSON.stringify($keeps.map(function() {return {url: this.querySelector('.keep-title>a').href}}).get()),
				contentType: 'application/json',
				error: showMessage.bind(null, 'Could not remove keeps, please try again later'),
				success: function(data) {
					$detail.children().removeAttr('data-kept');
					$detail.find('.page-coll').remove();
					$keeps.removeClass("mine").find(".keep-private").removeClass("on");
					var collCounts = $keeps.find(".keep-coll").remove().map(getDataId).get()
						.reduce(function(o, id) {o[id] = (o[id] || 0) + 1; return o}, {});
					for (var collId in collCounts) {
						$collList.find(".collection[data-id=" + collId + "]").find(".keep-count").text(collections[collId].keeps -= collCounts[collId]);
					}
				}});
		} else {  // toggle public/private
			howKept = howKept == "pub" ? "pri" : "pub";
			$detail.children().attr('data-kept', howKept).find('.page-how').attr('class', 'page-how ' + howKept);
			$keeps.each(function() {
				var $keep = $(this), keepLink = $keep.find('.keep-title>a')[0];
				$.ajax({
					url: urlKeeps + "/" + $keep.data('id') + "/update",  // TODO: support bulk operation with one server request
					type: "POST",
					dataType: 'json',
					data: JSON.stringify({title: keepLink.title, url: keepLink.href, isPrivate: howKept == 'pri'}),
					contentType: 'application/json',
					error: showMessage.bind(null, 'Could not update keep, please try again later'),
					success: function(data) {
						$keep.find('.keep-private').toggleClass('on', howKept == 'pri');
					}});
			});
		}
	}).on("click", ".page-coll-a", function(e) {
		e.preventDefault();
		navigate(this.href);
	}).on("click", ".page-coll-x", function(e) {
		e.preventDefault();
		removeKeepsFromCollection(
			$(this.parentNode).data("id"),
			$main.find(".keep.detailed").map(getDataId).get());
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
				return (s1.min - s2.min) || (s1.sum - s2.sum) || c1.name.localeCompare(c2.name, undefined, {numeric: true});
			}).splice(0, 4).map(function(c) {
				for (var name = escapeHTMLContent(c.name), i = re.length; i--;) {
					name = name.replace(new RegExp("^((?:[^&<]|&[^;]*;|<[^>]*>)*)\\b(" + re[i].source + ")", "gi"), "$1<b>$2</b>");
				}
				return {id: c.id, name: name};
			});
			if (!allColls.some(function(c) {return c.name.localeCompare(val, undefined, {usage: "search", sensitivity: "base"}) == 0})) {
				colls.push({id: "", name: val});
			}
		} else {
			colls = allColls.sort(function(c1, c2) {
				return c2.keeps - c1.keeps || c1.name.localeCompare(c2.name, undefined, {numeric: true});
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

	$(".send-feedback").click(function() {
		if (!window.UserVoice) {
			window.UserVoice = [];
			$.getScript("//widget.uservoice.com/2g5fkHnTzmxUgCEwjVY13g.js>");
		}
		UserVoice.push(['showLightbox', 'classic_widget', {
			mode: 'full',
			primary_color: '#cc6d00',
			link_color: '#007dbf',
			default_mode: 'support',
			forum_id: 200379,
			custom_template_id: 3305}]);
	});

	// load data for persistent (view-independent) page UI
	var promise = {
		me: $.getJSON(urlMe, function(data) {
			me = data;
			$(".my-pic").css("background-image", "url(" + formatPicUrl(data.id, data.pictureName, 200) + ")");
			$(".my-name").text(data.firstName + ' ' + data.lastName);
		}).promise(),
		myPrefs: $.getJSON(urlMyPrefs, function(data) {
			myPrefs = data;
			if (myPrefs.site_left_col_width) {
				$(".left-col").animate({width: +myPrefs.site_left_col_width}, 120);
			}
		}).promise(),
		collections: $.getJSON(urlCollectionsAll, {sort: "user"}, function(data) {
			collTmpl.render(data.collections);
			collections = data.collections.reduce(function(o, c) {o[c.id] = c; return o}, {});
		}).promise()};
	updateNumKeeps();

	// render initial view
	$(window).trigger('statechange');

	// auto-update my keeps every minute
	setInterval(addNewKeeps, 60000);
	setInterval(updateNumKeeps, 60000);
});
