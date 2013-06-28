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
var urlCollections = urlSite + '/collections';
var urlCollectionsAll = urlCollections + '/all';
var urlCollectionsOrder = urlCollections + '/ordering';
var urlCollectionsCreate = urlCollections + '/create';
var urlScreenshot = urlBase + '/screenshot';

$.ajaxSetup({
	xhrFields: {withCredentials: true},
	crossDomain: true});

$(function() {
	var $subtitle = $(".subtitle"), subtitleTmpl = Tempo.prepare($subtitle);

	var $myKeeps = $("#my-keeps"), $results = $("#search-results"), keepsTmpl = Tempo.prepare($myKeeps).when(TempoEvent.Types.RENDER_COMPLETE, function(ev) {
		hideLoading();

		var now = new Date;
		$(ev.element).find("time").easydate({set_title: false}).each(function() {
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
				var $keeps = ui.draggable.hasClass("selected") ? ui.draggable.parent().find(".keep.selected") : ui.draggable;
				var $coll = $(this), collId = $coll.data("id");
				$.ajax({
					url: urlKeepAdd,
					type: "POST",
					dataType: 'json',
					data: JSON.stringify({
						collectionId: collId,
						keeps: $keeps.map(function() {var a = this.querySelector(".keep-title>a"); return {title: a.title, url: a.href}}).get()}),
					contentType: 'application/json',
					error: onDropOnCollectionAjaxError,
					success: function(data) {
						var collName = collections[collId].name;
						$coll.find(".keep-count").text(collections[collId].keeps += data.addedToCollection);
						$keeps.addClass("mine")
							.find(".keep-colls:not(:has(.keep-coll[data-id=" + collId + "]))")
							.contents().filter(function() {return this.nodeType == 3}).remove().end().end()
							.append('<span class=keep-coll data-id=' + collId + '>' +
								'<a class="keep-coll-a" href="javascript:">' + collName + '</a><a class="keep-coll-x" href="javascript:"></a>' +
								'</span>');
						if ($keeps.is(".selected")) {
							$detail.attr("data-kept", $keeps.has(".keep-private.on").length == $keeps.length ? "pri" : "pub");
							if (!$inColl.has(".page-coll[data-id=" + collId + "]").length) {
								inCollTmpl.append({id: collId, name: collName});
							}
						}
					}
				});
			}};

	var $inColl = $(".page-coll-list").contents().filter(function() {return this.nodeType == 3}).remove().end().end();
	var inCollTmpl = Tempo.prepare($inColl);

	var me;
	var myKeepsCount;
	var searchResponse;
	var collections;
	var searchTimeout;
	var lastKeep;

	// populate user data
	$.getJSON(urlMe, function(data) {
		me = data;
		$(".my-pic").css("background-image", "url(" + formatPicUrl(data.id, data.pictureName, 200) + ")");
		$(".my-name").text(data.firstName + ' ' + data.lastName);
	});

	$.fn.layout = function() {
		return this.each(function() {this.clientHeight});  // forces layout
	};

	function onDropOnCollectionAjaxError() {
		showMessage('Could not add to collection, please try again later');
	}

	function daysBetween(date1, date2) {
		return Math.round((date2 - date1) / 86400000);  // ms in one day
	}

	function formatPicUrl(userId, pictureName, size) {
		return '//djty7jcqog9qu.cloudfront.net/users/' + userId + '/pics/' + size + '/' + pictureName;
	}

	function showLoading() {
		$('.keeps-loading').show();
	}
	function hideLoading() {
		$('.keeps-loading').hide();
	}
	function isLoading() {
		return $('.keeps-loading').is(':visible');
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

	function doSearch(context) {
		$('.left-col .active').removeClass('active');
		$main.attr("data-view", "search");
		subtitleTmpl.render({searching: true});
		// showDetails();
		showLoading();
		var q = $.trim($query.val());
		$query.attr("data-q", q || null);
		$.getJSON(urlSearch, {q: q, f: "a", maxHits: 30, context: context}, function(data) {
			searchResponse = data;
			subtitleTmpl.render({
				numShown: data.hits.length + (context ? $results.find(".keep").length : 0),
				query: data.query});
			data.hits.forEach(prepHitForRender);
			if (context == null) {
				keepsTmpl.into($results[0]).render(data.hits);
				hideDetails();
			} else {
				keepsTmpl.into($results[0]).append(data.hits);
			}
		});
	}

	function prepHitForRender(hit) {
		$.extend(hit, hit.bookmark);
		hit.me = me;
		hit.keepers = hit.users;
		hit.others = hit.count - hit.users.length - (hit.isMyBookmark && !hit.isPrivate ? 1 : 0);
	}

	function prepKeepForRender(keep) {
		keep.isMyBookmark = true;
		keep.me = me;
		for (var i = 0; i < keep.collections.length; i++) {
			var id = keep.collections[i];
			keep.collections[i] = {id: id, name: collections[id].name};
		}
	}

	function addNewKeeps() {
		var first = $myKeeps.find('.keep').first().data('id');
		var params = {after: first};
		if ($('.left-col h3.active').is('.collection')) {
			params.collection = $('.left-col h3.active').data('id');
		}
		console.log("Fetching 30 keep after " + first);
		$.getJSON(urlMyKeeps, params,
			function(data) {
				data.keeps.forEach(prepKeepForRender);
				keepsTmpl.into($myKeeps[0]).prepend(data.keeps);
				$myKeeps.find('.keep-group-title.today').prependTo($myKeeps);
			});
	}

	function showMyKeeps(collId) {
		collId = collId || null;
		var $h3 = $(".left-col h3");
		$h3.filter(".active").removeClass("active");
		$h3.filter(collId ? "[data-id='" + collId + "']" : ".my-keeps").addClass("active");
		$main.attr("data-view", "mine")
			.find("h1").text(collId ? collections[collId].name : "Browse your keeps");

		$results.empty();
		$query.val("").removeAttr("data-q");
		searchResponse = null;
		hideDetails();

		if ($myKeeps.data("collId") != collId || !("collId" in $myKeeps.data())) {
			$myKeeps.data("collId", collId).empty();
			lastKeep = null;
			loadKeeps(collId);
		} else {
			subtitleTmpl.render({
				numShown: $myKeeps.find(".keep").length,
				numTotal: collId ? collections[collId].keeps : myKeepsCount,
				collId: collId || undefined});
		}
	}

	function loadKeeps(collId) {
		if (lastKeep != "end") {
			showLoading();
			subtitleTmpl.render({});
			var params = {count: 30};
			if (collId) {
				params.collection = collId;
			}
			if (lastKeep) {
				params.before = lastKeep;
			}
			console.log("Fetching %d keeps %s", params.count, lastKeep ? "before " + lastKeep : "");
			$.getJSON(urlMyKeeps, params,
				function withKeeps(data) {
					if (!collections) {
						setTimeout(withKeeps.bind(null, data), 30);
						return;
					}
					subtitleTmpl.render({
						numShown: $myKeeps.find(".keep").length + data.keeps.length,
						numTotal: collId ? collections[collId].keeps : myKeepsCount,
						collId: collId || undefined});
					hideLoading();
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
					}
				});
		}
	}

	function populateCollections() {
		$.getJSON(urlCollectionsAll, {sort: "user"}, function(data) {
			collTmpl.render(data.collections);
			collections = data.collections.reduce(function(o, c) {o[c.id] = c; return o}, {});
		});
	}

	// function populateCollectionsRight() {
	// 	$.getJSON(urlCollectionsAll, {sort: "last_kept"} ,
	// 			function(data) {
	// 				$('.detail .actions .collections ul li:not(.create)').remove();
	//				for (var i in data.collections) {
	// 					$('.detail .actions .collections ul').append('<li><input type="checkbox" data-id="'+data.collections[i].id+'" id="cb-'+data.collections[i].id+'"/><label class="long-text" for="cb-'+data.collections[i].id+'"><span></span>'+data.collections[i].name+'</label></li>');
	//		 		}
	// 			});
	// }

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

	// delete/rename collection
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
				// $('.detail .collections ul li:has(input[data-id="'+collId+'"])').remove();
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
		}).closest(".collection").removeClass("with-menu").each(hideCollTri);
	}

	// handle collection adding/removing from right bar
	// $inColl.on('change', 'input[type="checkbox"]', function() {
	// 	// remove selected keeps from collection
	// 	var $row = $(this).closest('.row');
	// 	var colId = $(this).data('id');
	// 	var $keeps = $main.find(".keep.selected");
	// 	var keepIds = $keeps.map(getDataId).get();
	// 	$.ajax({
	// 		url: urlCollections + "/" + colId + "/removeKeeps",
	// 		type: "POST",
	// 		dataType: 'json',
	// 		data: JSON.stringify(keepIds),
	// 		contentType: 'application/json',
	// 		error: showMessage.bind(null, 'Could not remove keeps from collection, please try again later'),
	// 		success: function(data) {
	// 			console.log(data);
	// 			$collList.find(".collection[data-id=" + colId + "]").find(".keep-count").text(collections[colId].keeps -= data.removed);
	// 			$keeps.find(".keep-coll[data-id=" + colId + "]").remove();
	// 			$row.remove();
	// 		}});
	// });

	// $('.detail .actions .collections').on('change', 'input[type="checkbox"]', function() {
	// 	// add selected keeps to collection
	// 	var $row = $(this).closest('.row');
	// 	var colId = $(this).data('id');
	// 	var keeps = $main.find(".keep.selected").map(getDataId).get();
	// 	$.ajax({
	// 		url: urlCollections + "/" + colId + "/addKeeps",
	// 		type: "POST",
	// 		dataType: 'json',
	// 		data: JSON.stringify(keeps),
	// 		contentType: 'application/json',
	// 		error: showMessage.bind(null, 'Could not add keeps to collection, please try again later'),
	// 		success: function(data) {
	// 			console.log(data);
	// 			$('#collections-list>.collection[data-id="'+colId+'"] .keep-count').text(collections[colId].keeps += data.added);
	// 			if (!$inColl.find("#cb1-" + colId).length) {
	// 				inCollTmpl.append({id: colId, name: collections[colId].name});
	// 			}
	// 			$row.remove();
	// 		}});
	// });

	$(document).keydown(function(e) {  // auto focus on search field when starting to type anywhere on the document
		if (!$(e.target).is('input,textarea') && e.which >= 48 && e.which <= 90 && !e.ctrlKey && !e.metaKey && !e.altKey) {
			$query.focus();
		}
	});

	var $main = $(".main").on("mousedown", ".keep-checkbox", function(e) {
		e.preventDefault();  // avoid starting selection
	}).on("click", ".keep-coll-a", function(e) {
		e.stopPropagation(), e.preventDefault();
		var collId = $(this.parentNode).data("id");
		if (collId !== $collList.find(".collection.active").data("id")) {
			showMyKeeps(collId);
		}
	}).on("click", ".keep-coll-x", function(e) {
		e.stopPropagation(), e.preventDefault();
		var $coll = $(this.parentNode);
		removeKeepsFromCollection($coll.data("id"), [$coll.closest(".keep").data("id")]);
	}).on("click", ".keep", function(e) {
		// 1. Only one keep at a time can be selected and not checked.
		// 2. If a keep is selected and not checked, no keeps are checked.
		// 3. If a keep is checked, it is also selected.
		var $keep = $(this), $selected = $main.find(".keep.selected");
		if ($(e.target).hasClass("keep-checkbox")) {
			if ($(e.target).toggleClass("checked").hasClass("checked")) {
				$selected.not(":has(.keep-checkbox.checked)").removeClass("selected");
				$keep.addClass("selected");
			} else {
				$keep.removeClass("selected");
			}
		} else {
			var select = !$keep.is(".selected") || $selected.length != 1;
			$selected.not(this).removeClass("selected").end().find(".keep-checkbox").removeClass("checked");
			$keep.toggleClass("selected", select);
		}
		$selected = $main.find(".keep.selected");
		if (!$selected.length) {
			hideDetails();
		} else if ($selected.length > 1) {
			var howKept = $selected.not(".mine").length ? null :
				$selected.has(".keep-private.on").length == $selected.length ? "pri" : "pub";
			$detail.attr("data-kept", howKept).addClass("multiple");
			$('.page-title').text($selected.length + " keeps selected");
			$('.page-url').hide().empty().attr('href', '');
			$('.page-pic-wrap').hide();
			$('.page-pic').css('background-image', '');
			$('.page-who').hide();

			var collCounts = $selected.find('.keep-coll').map(getDataId).get()
				.reduce(function(o, id) {o[id] = (o[id] || 0) + 1; return o}, {});
			inCollTmpl.render(
				Object.keys(collCounts)
					.sort(function(id1, id2) {return collCounts[id1] - collCounts[id2]})
					.map(function(id) {return {id: id, name: collections[id].name}}));
			showDetails();
		} else { // one keep is selected
			$keep = $selected;
			var howKept = $keep.is('.mine') ? ($keep.has('.keep-private.on').length ? "pri" : "pub") : null;
			var $keepLink = $keep.find('.keep-title>a'), url = $keepLink[0].href;
			$detail.attr("data-kept", howKept).removeClass("multiple");
			$('.page-title').text($keepLink.text());
			$('.page-url').attr('href', url).text(url).show();
			$('.page-pic').css("background-image", "url(" + urlScreenshot + '?url=' + escape(url) + ")");
			$('.page-pic-wrap').show();
			$('.page-how').attr('class', 'page-how ' + (howKept || ''));
			$('.page-who-pics').empty().append($keep.find(".keep-who>img").clone());
			$('.page-who-text').html($keep.find(".keep-who-text").html());
			$('.page-who').show();

			inCollTmpl.render($keep.find('.keep-coll').map(function() {
				var id = $(this).data('id');
				return {id: id, name: collections[id].name};
			}).get());
			showDetails();
		}
	});
	var $mainHead = $(".main-head");
	var $mainKeeps = $(".main-keeps").antiscroll({x: false, width: "100%"});
	$mainKeeps.find(".antiscroll-inner").scroll(function() { // infinite scroll
		var sT = this.scrollTop;
		$mainHead.toggleClass("scrolled", sT > 0);
		if (!isLoading() && this.clientHeight + sT > this.scrollHeight - 300) {
			if (searchResponse) {
				doSearch(searchResponse.context);
			} else {
				loadKeeps($myKeeps.data("collId"));
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
			console.log("[no change]");
			return;  // no change
		} else if (!q) {
			showMyKeeps();
		} else if (e.which) {
			if (e.which == 13) { // Enter
				console.log("[doSearch]");
				doSearch();
			}
		} else {
			console.log("[setTimeout]");
			searchTimeout = setTimeout(doSearch, 500);  // instant search
		}
	});

	var $collList = $("#collections-list")
	.each(function() {this.style.top = this.offsetTop + "px"})
	.addClass("positioned")
	.antiscroll({x: false, width: "100%"})
	.sortable({
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
	}).on("mousemove", ".collection:not(.with-menu):not(.renaming):not(.drop-hover)", function(e) {
		var $coll = $(this), $tri = $coll.find(".coll-tri"), data = $coll.data();
		var x = e.clientX, y = e.clientY, dx = x - data.x, dy = y - data.y, now = +new Date;
		if ($coll.hasClass("with-tri")) {
			if (!$coll.hasClass("no-tri") && e.target !== $tri[0] && dx > Math.abs(dy)) {
				hideCollTri.call(this);
			}
		} else {
			var r = this.getBoundingClientRect();
			if (x < r.left + .5 * r.width &&
					(-4 * dx > now - data.t && -dx > Math.abs(dy) || e.target === $tri[0] ||
					 dx < 0 && x < r.left + $tri.width())) {
				$coll.addClass("with-tri");
			}
		}
		data.x = x, data.y = y, data.t = now;
	}).on("mouseleave", ".collection.with-tri:not(.with-menu):not(.no-tri)", function() {
		hideCollTri.call(this);
	});
	var collScroller = $collList.data("antiscroll");
	$(window).resize(collScroller.refresh.bind(collScroller));

	function hideCollTri() {
		$(this).on("transitionend", function end(e) {
			if (e.target === this) {
				$(this).off("transitionend", end).removeClass("with-tri no-tri");
			}
		}).addClass("no-tri");
	}

	$(".left-col>.my-keeps>a").click(function() {
		showMyKeeps();
		addNewKeeps();
	});

	$colls.on("click", "h3.collection>a", function(e) {
		var $a = $(this), $coll = $a.parent();
		if ($coll.hasClass("renaming")) {
			if (e.target === this) {
				$a.find("input").focus();
			}
		} else {
			showMyKeeps($coll.data("id"));
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
				createCollection(name);
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

	// filter collections or right bar
	// $('.detail .collections input.find').keyup(function() {
	// 	var re = new RegExp(this.value, "gi");
	// 	$('.detail .collections ul li:not(.create)').each(function() {
	// 		$(this).toggle(re.test($(this).find('label').text()));
	// 	});
	// });

	function createCollection(name) {
		$newColl.addClass("submitted");
		$.ajax({
			url: urlCollectionsCreate,
			type: "POST",
			dataType: 'json',
			data: JSON.stringify({name: name}),
			contentType: 'application/json',
			error: function() {
				showMessage('Could not create collection, please try again later');
				$newColl.removeClass("submitted");
			},
			success: function(data) {
				collTmpl.prepend(collections[data.id] = {id: data.id, name: name, keeps: 0});
				$newColl.hide().removeClass("submitted").find("input").val("").prop("disabled", true);
				// TODO: Use Tempo template!!
				// $('.detail .actions .collections ul li.create')
				// 	.after('<li><input type="checkbox" data-id="' + data.id + '" id="cb-' + data.id + '"><label class="long-text" for="cb-' + data.id + '"><span></span>' + name + '</label></li>');
			}});
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
		if ($keeps.is(".selected") && !$allKeeps.filter(".selected").not($keeps).has(".keep-coll[data-id=" +  + "]").length) {
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

	// $('.detail .actions a.add').click(function() {
	// 	$(this).toggleClass('active');
	// 	$('.detail .collections').toggleClass('active');
	// });

	var $detail = $('.detail');

	// keep / unkeep
	$detail.on("click", '.page-keep,.page-priv', function(e) {
		var $keeps = $main.find(".keep.selected");
		var $a = $(this), howKept = $detail.attr("data-kept");
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
					$detail.attr('data-kept', howKept).find('.page-how').attr('class', 'page-how ' + howKept);
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
					$detail.removeAttr('data-kept');
					$keeps.removeClass("mine").find(".keep-private").removeClass("on");
					// TODO: decrement all relevant collection counts
				}});
		} else {  // toggle public/private
			howKept = howKept == "pub" ? "pri" : "pub";
			$detail.attr('data-kept', howKept).find('.page-how').attr('class', 'page-how ' + howKept);
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
		var collId = $(this.parentNode).data("id");
		if (collId !== $collList.find(".collection.active").data("id")) {
			showMyKeeps(collId);
		}
	}).on("click", ".page-coll-x", function(e) {
		e.preventDefault();
		removeKeepsFromCollection(
			$(this.parentNode).data("id"),
			$main.find(".keep.selected").map(getDataId).get());
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

	populateCollections();
	// populateCollectionsRight();

	updateNumKeeps();  // populate number of my keeps
	showMyKeeps();     // populate all my keeps

	// auto update my keeps every minute
	setInterval(addNewKeeps, 60000);
	setInterval(updateNumKeeps, 60000);
});
