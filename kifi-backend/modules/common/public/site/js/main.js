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

$.ajaxSetup({
  xhrFields: {withCredentials: true},
  crossDomain: true});

$(function() {

	var $subtitle = $(".subtitle"), subtitleTmpl = Tempo.prepare($subtitle);
	var $myKeeps = $("#my-keeps"), myKeepsTmpl = Tempo.prepare($myKeeps).when(TempoEvent.Types.RENDER_COMPLETE, function(event) {
		hideLoading();
		$myKeeps.find(".keep-who").each(function() {
			$(this).find('img.small-avatar').prependTo(this);  // eliminating whitespace text nodes?
		}).filter(":not(:has(.me))")
			.prepend('<img class="small-avatar me" src="' + formatPicUrl(me.id, me.pictureName, 100) + '">');
		initDraggable();

		$("time").easydate({set_title: false});

		// insert time sections
		var now = new Date;
		$myKeeps.find('.keep').each(function() {
			var age = daysBetween(new Date($(this).data('created')), now);
			if ($myKeeps.find('li.keep-group-title.today').length == 0 && age <= 1) {
				$(this).before('<li class="keep-group-title today">Today</li>');
			} else if ($myKeeps.find('li.keep-group-title.yesterday').length == 0 && age > 1 && age < 2) {
				$(this).before('<li class="keep-group-title yesterday">Yesderday</li>');
			} else if ($myKeeps.find('li.keep-group-title.week').length == 0 && age >= 2 && age <= 7) {
				$(this).before('<li class="keep-group-title week">Past Week</li>');
			} else if ($myKeeps.find('li.keep-group-title.older').length == 0 && age > 7) {
				$(this).before('<li class="keep-group-title older">Older</li>');
			}
		});
	});
	var $results = $("#search-results"), searchTemplate = Tempo.prepare($results).when(TempoEvent.Types.RENDER_COMPLETE, function(event) {
		hideLoading();
		initDraggable();
		$results.find(".keep-who").each(function() {
			$(this).find('img.small-avatar').prependTo(this);  // eliminating whitespace text nodes?
		});
		$results.find(".keep.mine .keep-who:not(:has(.me))")
			.prepend('<img class="small-avatar me" src="' + formatPicUrl(me.id, me.pictureName, 100) + '">');
	});
	var $colls = $("#collections"), collTmpl = Tempo.prepare($colls).when(TempoEvent.Types.RENDER_COMPLETE, function(event) {
		makeCollectionsDroppable($colls.find(".collection"));
		adjustCollHeight();
	});
	var $inColl = $(".in-collections"), inCollTmpl = Tempo.prepare($inColl);

	var me;
	var myKeepsCount;
	var searchResponse;
	var collections = {};
	var searchTimeout;
	var lastKeep;

	$.fn.layout = function() {
	  return this.each(function() {this.clientHeight});  // forces layout
	};

	function unique(arr) {
	  return $.grep(arr, function(v, k) {
	    return $.inArray(v, arr) === k;
	  });
	}

	function initDraggable() {
		$(".draggable").draggable({
			revert: "invalid",
			handle: ".handle",
			cursorAt: { top: 15, left: 0 },
			helper: function() {
				var text = $(this).find('a').first().text();
				var numSelected = $main.find(".keep.selected").length;
				if (numSelected > 1)
					text = numSelected + " selected keeps";
				return $('<div class="drag-helper">').html(text);
			}
		});
	}

	function makeCollectionsDroppable($c) {
		$c.droppable({
			accept: ".keep",
			greedy: true,
			tolerance: "pointer",
			hoverClass: "drop-hover",
			drop: onDropOnCollection});
	}

	function onDropOnCollection(event, ui) {
		var $keeps = ui.draggable, coll = this;
		if ($keeps.hasClass("selected")) {
			$keeps = $keeps.add($main.find(".keep.selected"));
		}

		var myKeepIds = $keeps.filter(".mine").map(function() {return $(this).data("id")}).get();

		// may first need to keep any keeps that are not mine yet
		var $notMine = $keeps.filter(":not(.mine)");
		if ($notMine.length) {
			$.ajax({
				url: urlKeepAdd,
				type: "POST",
				dataType: 'json',
				data: JSON.stringify($notMine.map(function() {var a = this.querySelector("a"); return {title: a.title, url: a.href}}).get()),
				contentType: 'application/json',
				error: onDropOnCollectionAjaxError,
				success: function(data) {
					myKeepIds.push.apply(myKeepIds, data.keeps.map(function(k) {return k.id}));
					addMyKeepsToCollection.call(coll, myKeepIds);
				}
			});
		} else {
			addMyKeepsToCollection.call(coll, myKeepIds);
		}
	}

	function addMyKeepsToCollection(keepIds) {
		var $coll = $(this), collId = $coll.data("id");
		$.ajax({
			url: urlCollections + '/' + collId + '/addKeeps',
			type: "POST",
			dataType: 'json',
			data: JSON.stringify(keepIds),
			contentType: 'application/json',
			error: onDropOnCollectionAjaxError,
			success: function(data) {
				$coll.find(".keep-count").text(collections[collId].keeps += data.added);
				if (!$inColl.find("#cb1-" + collId).length) {
					inCollTmpl.append({id: collId, name: collections[collId].name});
				}
			}});
	}

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
		$('div.loading').show();
	}
	function hideLoading() {
		$('div.loading').hide();
	}
	function isLoading() {
		return $('div.loading').is(':visible');
	}

	function showRightSide() {
		var $r = $('aside.right').off("transitionend"), d;
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
	function hideRightSide() {
		var d = $(window).width() - $main[0].getBoundingClientRect().right;
		$('aside.right').on("transitionend", function end(e) {
			if (e.target === this) {
				$(this).off("transitionend").css({display: "", transform: ""});
			}
		}).css({transform: "translate(" + d + "px,0)", "transition-timing-function": "ease-in"});
	}

	function doSearch(context) {
		$('.left-col .active').removeClass('active');
		$main.attr("data-view", "search");
		subtitleTmpl.render({searching: true});
		// showRightSide();
		showLoading();
		var q = $.trim($query.val());
		$query.attr("data-q", q || null);
		$.getJSON(urlSearch, {q: q, f: "a", maxHits: 30, context: context}, function(data) {
			searchResponse = data;
			subtitleTmpl.render({
				numShown: data.hits.length + (context ? $results.find(".keep").length : 0),
				query: data.query});
			if (context == null) {
				searchTemplate.render(data.hits);
				hideRightSide();
			} else {
				searchTemplate.append(data.hits);
			}
		});
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
				myKeepsTmpl.prepend(data.keeps);
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

		searchTemplate.clear();
		$query.val("").removeAttr("data-q");
		searchResponse = null;
		hideRightSide();

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
				function(data) {
					subtitleTmpl.render({
						numShown: $myKeeps.find(".keep").length + data.keeps.length,
						numTotal: collId ? collections[collId].keeps : myKeepsCount,
						collId: collId || undefined});
					hideLoading();
					if (!data.keeps.length) {  // no more
						lastKeep = "end";
					} else {
						if (lastKeep == null) {
							myKeepsTmpl.render(data.keeps);
						} else {
							myKeepsTmpl.append(data.keeps);
						}
						lastKeep = data.keeps[data.keeps.length - 1].id;
					}
				});
		}
	}

	function populateCollections() {
		$.getJSON(urlCollectionsAll, {sort: "user"}, function(data) {
			collTmpl.render(data.collections);
		});
	}

	function populateCollectionsRight() {
		$.getJSON(urlCollectionsAll, {sort: "last_kept"} ,
				function(data) {
					$('aside.right .actions .collections ul li:not(.create)').remove();
					for (i in data.collections) {
						collections[data.collections[i].id] = data.collections[i];
						$('aside.right .actions .collections ul').append('<li><input type="checkbox" data-id="'+data.collections[i].id+'" id="cb-'+data.collections[i].id+'"/><label class="long-text" for="cb-'+data.collections[i].id+'"><span></span>'+data.collections[i].name+'</label></li>');
					}
				});
	}

	function updateNumKeeps() {
		$.getJSON(urlMyKeepsCount, function(data) {
			$('.left-col .my-keeps .keep-count').text(myKeepsCount = data.numKeeps);
		});
	}

	function showMessage(msg) {
		$.fancybox($('<p>').text(msg));
	}

	function adjustCollHeight() {
		$collList.height($(window).height() - $collList.offset().top);
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
		var $coll = $collMenu.prev(".collection");
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
				$('aside.right .collections ul li:has(input[data-id="'+collId+'"])').remove();
			}});
	}).on("mouseup mousedown", ".coll-rename", function(e) {
		if (e.which > 1) return;
		hideCollMenu();
		var $coll = $collMenu.prev(".collection").addClass("renaming");
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
		}).prev(".collection").removeClass("with-menu").each(hideCollTri);
	}

	// auto update my keeps every minute
	setInterval(addNewKeeps, 60000);
	setInterval(updateNumKeeps, 60000);

	$(window).resize(adjustCollHeight);

	// handle collection adding/removing from right bar
	$inColl.on('change', 'input[type="checkbox"]', function() {
		// remove selected keeps from collection
		var $row = $(this).closest('.row');
		var colId = $(this).data('id');
		var keepIds = $main.find(".keep.selected").map(function() {return $(this).data('id')}).get();
		$.ajax({
			url: urlCollections + "/" + colId + "/removeKeeps",
			type: "POST",
			dataType: 'json',
			data: JSON.stringify(keepIds),
			contentType: 'application/json',
			error: showMessage.bind(null, 'Could not remove keeps from collection, please try again later'),
			success: function(data) {
				console.log(data);
				$('#collections-list>.collection[data-id="'+colId+'"] .keep-count').text(collections[colId].keeps -= data.removed);
				$row.remove();
			}});

	});
	$('aside.right .actions .collections').on('change', 'input[type="checkbox"]', function() {
		// add selected keeps to collection
		var $row = $(this).closest('.row');
		var colId = $(this).data('id');
		var keeps = $main.find(".keep.selected").map(function() {return $(this).data('id')}).get();
		$.ajax({
			url: urlCollections + "/" + colId + "/addKeeps",
			type: "POST",
			dataType: 'json',
			data: JSON.stringify(keeps),
			contentType: 'application/json',
			error: showMessage.bind(null, 'Could not add keeps to collection, please try again later'),
			success: function(data) {
				console.log(data);
				$('#collections-list>.collection[data-id="'+colId+'"] .keep-count').text(collections[colId].keeps += data.added);
				if (!$inColl.find("#cb1-" + colId).length) {
					inCollTmpl.append({id: colId, name: collections[colId].name});
				}
				$row.remove();
			}});
	});

	$(document).keydown(function(e) {  // auto focus on search field when starting to type anywhere on the document
		if (!$(e.target).is('input,textarea')) {
			$query.focus();
		}
	});

	var $main = $(".main").on("click", ".keep", function(e) {
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
		var $title = $('aside.right>.title');
		var $who = $('aside.right>.who-kept');
		if (!$selected.length) {
			hideRightSide();
		} else if ($selected.length > 1) {
			$title.find('h2').text($selected.length + " keeps selected");
			$title.find('a').empty();
			$who.empty();
			var allCol = [], ids;
			$selected.each(function() {
				$who.append('<div class=long-text>' + $(this).find('a').first().html() + '</div>');
				if (ids = $(this).data('collections').length) {
					allCol.push.apply(allCol, ids.split(','));
				}
			});
			allCol = unique(allCol);
			$inColl.empty();
			for (i in allCol) {
				inCollTmpl.append({id: allCol[i], name: collections[allCol[i]].name});
			}
			showRightSide();
		} else { // one keep is selected
			$keep = $selected.first();
			$title.find('h2').text($keep.find('a').first().text());
			var url = $keep.find('a').first().attr('href');
			$title.find('a').text(url).attr('href', url).attr('target', '_blank');
			$who.html($keep.find(".keep-who").html());
			$who.find('span').prependTo($who).removeClass('fs9 gray');
			$inColl.empty();
			if ($keep.data('collections')) {
				$keep.data('collections').split(',').forEach(function(id) {
					inCollTmpl.append({id: id, name: collections[id].name});
				});
			}
			var $btn = $('aside.right .keepit .keep-button');
			if ($keep.is('.mine')) {
				$btn.addClass('kept').toggleClass('private', !!$keep.has('.keep-private.on').length).find('.text').text('kept');
			} else {
				$btn.removeClass('kept private').find('.text').text('keep it');
			}
			showRightSide();
		}
	})
	.find(".scrollable").scroll(function() { // infinite scroll
		var sT = this.scrollTop;
		$(this.previousElementSibling).toggleClass("scrolled", sT > 0);
		if (!isLoading() && this.clientHeight + sT > this.scrollHeight - 300) {
			if (searchResponse) {
				doSearch(searchResponse.context);
			} else {
				loadKeeps($myKeeps.data("collId"));
			}
		}
	});

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

	$(".fancybox").fancybox();

	// populate user data
	$.getJSON(urlMe, function(data) {
		me = data;
		$(".my-pic").css("background-image", "url(" + formatPicUrl(data.id, data.pictureName, 200) + ")");
		$(".my-name").text(data.firstName + ' ' + data.lastName);
	});

	populateCollections();
	populateCollectionsRight();

	var $collList = $("#collections-list").sortable({
		items: ".collection",
		cancel: ".coll-tri,.renaming",
		opacity: .6,
		placeholder: "sortable-placeholder",
		beforeStop: function(event, ui) {
			// update the collection order
			$.ajax({
				url: urlCollectionsOrder,
				type: "POST",
				async: false,
				dataType: 'json',
				data: JSON.stringify($(this).find(".collection").map(function() {return $(this).data("id")}).get()),
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
		$collMenu.hide().insertAfter($coll).slideDown(80).data("docMouseDown", docMouseDown);
		document.addEventListener("mousedown", docMouseDown, true);
		function docMouseDown(e) {
			if (!e.button && !$.contains($collMenu[0], e.target)) {
	      hideCollMenu();
	    }
		}
	}).on("mousemove", ".collection:not(.with-menu):not(.renaming)", function(e) {
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

	function hideCollTri() {
		$(this).on("transitionend", function end(e) {
			if (e.target === this) {
				$(this).off("transitionend", end).removeClass("with-tri no-tri");
			}
		}).addClass("no-tri");
	}

	// populate number of my keeps
	updateNumKeeps();

	// populate all my keeps
	showMyKeeps();

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
	.on("click", ".new-collection", function() {
		clearTimeout(hideAddCollTimeout), hideAddCollTimeout = null;
		if (!$addColl.is(":animated")) {
			if ($addColl.is(":visible")) {
				$addColl.slideUp(80).find("input").val("").prop("disabled", true);
			} else {
				$addColl.slideDown(80, function() {
					$addColl.find("input").prop("disabled", false).focus().select();
				});
			}
		}
	});

	var $addColl = $colls.find(".add-collection"), hideAddCollTimeout;
	$addColl.find("input").on("blur keydown", function(e) {
		if ((e.which === 13 || e.type === "blur") && !$addColl.is(":animated")) { // 13 is Enter
			var name = $.trim(this.value);
			if (name) {
				createCollection(name);
			} else if (e.type === "blur") {
				if ($addColl.is(":visible"))
				// avoid back-to-back hide/show animations if "new collection" clicked again
				hideAddCollTimeout = setTimeout(hide.bind(this), 300);
			} else {
				e.preventDefault();
				hide.call(this);
			}
		} else if (e.which === 27 && !$addColl.is(":animated")) { // 27 is Esc
			hide.call(this);
		}
		function hide() {
			this.value = "";
			this.disabled = true;
			this.blur();
			$addColl.slideUp(200);
			clearTimeout(hideAddCollTimeout), hideAddCollTimeout = null;
		}
	}).focus(function() {
		clearTimeout(hideAddCollTimeout), hideAddCollTimeout = null;
	});

	// filter collections or right bar
	$('aside.right .collections input.find').keyup(function() {
		var re = new RegExp(this.value, "gi");
		$('aside.right .collections ul li:not(.create)').each(function() {
			$(this).toggle(re.test($(this).find('label').text()));
		});
	});

	function createCollection(name) {
		$addColl.addClass("submitted");
		$.ajax({
			url: urlCollectionsCreate,
			type: "POST",
			dataType: 'json',
			data: JSON.stringify({name: name}),
			contentType: 'application/json',
			error: function() {
				showMessage('Could not create collection, please try again later');
				$addColl.removeClass("submitted");
			},
			success: function(data) {
				collTmpl.prepend(collections[data.id] = {id: data.id, name: name, keeps: 0});
				$addColl.hide().removeClass("submitted").find("input").val("").prop("disabled", true);
				// TODO: Use Tempo template!!
				$('aside.right .actions .collections ul li.create')
					.after('<li><input type="checkbox" data-id="' + data.id + '" id="cb-' + data.id + '"><label class="long-text" for="cb-' + data.id + '"><span></span>' + name + '</label></li>');
			}});
	}

	$('aside.right .actions a.add').click(function() {
		$(this).toggleClass('active');
		$('aside.right .collections').toggleClass('active');
	})

	// keep / unkeep
	$('aside.right .keepit .keep-button').click(function(e) {
		var keepButton = $(this);
		var keeps = $main.find(".keep.selected");
		if (keepButton.hasClass('kept') && !$(e.target).is('span.private')) {
			$.ajax( {url: urlKeepRemove
				,type: "POST"
				,dataType: 'json'
				,data: JSON.stringify(keeps.map(function() {return {url: $(this).find('a').first().attr('href')}}).get())
				,contentType: 'application/json'
				,error: function() {showMessage('Could not remove keeps, please try again later')}
				,success: function(data) {
					keepButton.removeClass('kept');
					keepButton.find('span.text').text('keep it');
				}
			});
		} else if (keepButton.hasClass('kept') && $(e.target).is('span.private')) {
			keepButton.toggleClass('private');
			keeps.each(function() {
				// toggle private state
				var keep = $(this);
				var keepId = keep.data('id');
				var link = keep.find('a').first();
				$.ajax( {url: urlKeeps + "/" + keepId + "/update"
					,type: "POST"
					,dataType: 'json'
					,data: JSON.stringify({title: link.attr('title'), url: link.attr('href') ,isPrivate: keepButton.is('.private') })
					,contentType: 'application/json'
					,error: function() {showMessage('Could not update keep, please try again later')}
					,success: function(data) {
						keep.find('.keep-private').toggleClass('on', keepButton.is('.private'));
					}
				});
			});
		} else {
			if ($(e.target).is('span.private'))
				keepButton.addClass('private');
			// add selected keeps
			$.ajax( {url: urlKeepAdd
				,type: "POST"
				,dataType: 'json'
				,data: JSON.stringify(keeps.map(function() {return { title: $(this).find('a').first().attr('title')
					,url: $(this).find('a').first().attr('href')
					,isPrivate: keepButton.is('.private')}}).get())
				,contentType: 'application/json'
				,error: function() {showMessage('Could not add keeps, please try again later')}
				,success: function(data) {
					keepButton.addClass('kept');
					keepButton.find('span.text').text('kept');
				}
			});
		}
	});
});
