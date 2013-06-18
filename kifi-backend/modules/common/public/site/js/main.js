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
var urlConnections = urlUser + '/connections';
var urlCollections = urlSite + '/collections';
var urlCollectionsAll = urlCollections + '/all';
var urlCollectionsOrder = urlCollections + '/ordering';
var urlCollectionsCreate = urlCollections + '/create';

$.ajaxSetup({
  xhrFields: {withCredentials: true},
  crossDomain: true});

$(function() {

	var $myKeeps = $("#my-keeps"), myKeepsTmpl = Tempo.prepare($myKeeps).when(TempoEvent.Types.RENDER_COMPLETE, function(event) {
		hideLoading();
		$myKeeps.find(".keep .bottom").each(function() {
			$(this).find('img.small-avatar').prependTo(this);  // eliminating whitespace text nodes?
		}).filter(":not(:has(.me))")
			.prepend('<img class="small-avatar me" src="' + formatPicUrl(me.id, me.pictureName, 100) + '">');
		initDraggable();

		$(".easydate").easydate({set_title: false});

		// insert time sections
		var now = new Date;
		$myKeeps.find('.keep').each(function() {
			var age = daysBetween(new Date($(this).data('created')), now);
			if ($myKeeps.find('li.search-section.today').length == 0 && age <= 1) {
				$(this).before('<li class="search-section today">Today</li>');
			} else if ($myKeeps.find('li.search-section.yesterday').length == 0 && age > 1 && age < 2) {
				$(this).before('<li class="search-section yesterday">Yesderday</li>');
			} else if ($myKeeps.find('li.search-section.week').length == 0 && age >= 2 && age <= 7) {
				$(this).before('<li class="search-section week">Past Week</li>');
			} else if ($myKeeps.find('li.search-section.older').length == 0 && age > 7) {
				$(this).before('<li class="search-section older">Older</li>');
			}
		});
	});
	var $results = $("#search-results"), searchTemplate = Tempo.prepare($results).when(TempoEvent.Types.RENDER_COMPLETE, function(event) {
		hideLoading();
		initDraggable();
		$results.find(".keep .bottom").each(function() {
			$(this).find('img.small-avatar').prependTo(this);  // eliminating whitespace text nodes?
		});
		$results.find(".keep.mine .bottom:not(:has(.me))")
			.prepend('<img class="small-avatar me" src="' + formatPicUrl(me.id, me.pictureName, 100) + '">');
		$('.search>.num-results').text('Showing ' + $results.find('.keep').length + ' for "' + searchResponse.query + '"');
	});
	var $colls = $("#collections"), collTmpl = Tempo.prepare($colls).when(TempoEvent.Types.RENDER_COMPLETE, function(event) {
		makeCollectionsDroppable($colls.find(".collection"));
		adjustCollHeight();
	});
	var $inColl = $(".in-collections"), inCollTmpl = Tempo.prepare($inColl);

	var me;
	var searchResponse;
	var connections = {};
	var collections = {};
	var connectionNames = [];
	var searchTimeout;
	var lastKeep;
	var prevCollection;

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
				var $count = $coll.find("a span.right");
				$count.text(+$count.text() + data.added);
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
		$('div.loading').fadeIn();
	}
	function hideLoading() {
		$('div.loading').fadeOut();
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
		$myKeeps.hide().find('.keep.selected').removeClass('selected').find('input[type="checkbox"]').prop('checked', false);
		$('.left-col .active').removeClass('active');
		$('.search h1').hide();
		$('.search>.num-results').show();
		//$('aside.right').show();
		showLoading();
		$results.show();
		var q = $.trim($query.val());
		$query.attr("data-q", q || null);
		$.getJSON(urlSearch, {
			maxHits: 30,
			f: $('select[name="keepers"]').val() == 'c' ? $('#custom-keepers').textext()[0].tags().tagElements().find('.text-label').map(function(){return connections[$(this).text()]}).get().join('.') : $('select[name="keepers"]').val(),
			q: q,
			context: context
		},
		function(data) {
			searchResponse = data;
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
		if ($('.left-col h3.active').is('.collection'))
			params.collection = $('.left-col h3.active').data('id');
		console.log("Fetching 30 keep after " + first);
		$.getJSON(urlMyKeeps, params,
			function(data) {
				myKeepsTmpl.prepend(data.keeps);
				$myKeeps.find('.search-section.today').prependTo($myKeeps);
			});
	}

	function populateMyKeeps(id) {
		var params = {count: 30};
		$('.active').removeClass('active');
		if (id == null) {
			$('.left-col h3.my-keeps').addClass('active');
			$main.find(".search h1").text('Browse your keeps');
		} else {
			$('.left-col h3[data-id="' + id + '"]').addClass('active');
			params.collection = id;
			$main.find(".search h1").text('Browse your ' + collections[id].name + ' collection');
		}
		if (prevCollection != id) { // reset search if not fetching the same collection
			prevCollection = id;
			lastKeep = null;
			myKeepsTmpl.clear();
			hideRightSide();
		}
		searchTemplate.clear();
		$query.val("").removeAttr("data-q");
		searchResponse = null;
	//	$('aside.right').hide();
		$('.search>h1').show();
		$('.search>.num-results').hide();
		if (lastKeep == null) {
			$myKeeps.find('.search-section').remove();
		} else {
			params.before = lastKeep;
		}
		$myKeeps.show();
		if (lastKeep != "end") {
			showLoading();
			console.log("Fetching %d keeps %s", params.count, lastKeep ? "before " + lastKeep : "");
			$.getJSON(urlMyKeeps, params,
				function(data) {
					if (data.keeps.length == 0) { // end of results
						lastKeep = "end"; hideLoading(); return true;
					} else if (lastKeep == null) {
						myKeepsTmpl.render(data.keeps);
					} else {
						myKeepsTmpl.append(data.keeps);
					}
					lastKeep = data.keeps[data.keeps.length - 1].id;
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
			$('.left-col .my-keeps span').text(data.numKeeps);
		});
	}

	function showMessage(msg) {
		$.fancybox($('<p>').text(msg));
	}

	function adjustCollHeight() {
		$collList.height($(window).height() - $collList.offset().top);
	}

	// delete/rename collection
	var $collMenu = $("#coll-menu").on("mousedown", ".coll-remove", function(e) {
		if (e.which > 1) return;
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
				$coll.slideUp(80, $coll.remove.bind($coll));
				$('aside.right .collections ul li:has(input[data-id="'+collId+'"])').remove();
				// TODO: update center column
			}});
	}).on("mousedown", ".coll-rename", function(e) {
		if (e.which > 1) return;
		var $coll = $collMenu.prev(".collection").addClass("renaming").removeAttr("title");
		var $a = $coll.find(".coll-name"), name = $a.text();
		var $in = $("<input type=text placeholder='Type new collection name'>").val(name).data("orig", name);
		$a.empty().append($in);
		setTimeout(function() {
			$in[0].setSelectionRange(0, name.length, "backward");
			$in[0].focus();
		});
		$in.on('blur keydown', function(e) {
			if (e.which === 13 || e.type === "blur") { // 13 is Enter
				var oldName = $in.data("orig");
				var newName = $.trim(this.value) || oldName;
				if (newName !== oldName) {
					$.ajax({
						url: urlCollections + "/" + $coll.data('id') + "/update",
						type: "POST",
						dataType: 'json',
						data: JSON.stringify({name: newName}),
						contentType: 'application/json',
						error: function() {
							showMessage('Could not rename collection, please try again later');
							$a.text(oldName).prop("title", oldName);
						},
						success: function() {
							// TODO: update center column
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
			$a.text(name).prop("title", name);
			$coll.removeClass("renaming");
		}
	});

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
				var $count = $('.left-col .collection[data-id="'+colId+'"]').find('a span.right');
				$count.text($count.text() - data.removed);
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
				var $count = $('.left-col .collection[data-id="'+colId+'"]').find('a span.right');
				$count.text(+$count.text()  + data.added);
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
		if (e.target.type === "checkbox") {
			if (e.target.checked) {
				$selected.not(":has(.handle>input:checked)").removeClass("selected");
				$keep.addClass("selected");
			} else {
				$keep.removeClass("selected");
			}
		} else {
			var select = !$keep.is(".selected") || $selected.length != 1;
			$selected.not(this).removeClass("selected").end().find(".handle>input").prop("checked", false);
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
			$who.html($keep.find('div.bottom').html());
			$who.find('span').prependTo($who).removeClass('fs9 gray');
			$inColl.empty();
			if ($keep.data('collections')) {
				$keep.data('collections').split(',').forEach(function(id) {
					inCollTmpl.append({id: id, name: collections[id].name});
				});
			}
			var $btn = $('aside.right .keepit .keep-button');
			if ($keep.is('.mine')) {
				$btn.addClass('kept').toggleClass('private', $keep.is('.private')).find('.text').text('kept');
			} else {
				$btn.removeClass('kept private').find('.text').text('keep it');
			}
			showRightSide();
		}
	}).scroll(function() { // infinite scroll
		if (!isLoading() && this.clientHeight + this.scrollTop > this.scrollHeight - 300) {
			if (searchResponse) {
				doSearch(searchResponse.context);
			} else {
				populateMyKeeps($colls.find(".collection.active").data("id"));
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
			console.log("[populateMyKeeps]");
			populateMyKeeps();
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
		if ($collMenu.is(":animated")) return;
		var $tri = $(this), $coll = $tri.closest(".collection").addClass("with-menu");
		$collMenu.insertAfter($coll).slideDown(80);
		document.addEventListener("mousedown", function down(e) {
			if (e.button > 0) return;
			document.removeEventListener("mousedown", down, true);
			$collMenu.slideUp(80, $collMenu.detach.bind($collMenu));
			$coll.removeClass("with-menu").each(hideCollTri);
		}, true);
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
	populateMyKeeps();

	// populate user connections
	$.getJSON(urlConnections, function(data) {
		for (i in data.connections) {
			var name = data.connections[i].firstName + ' ' + data.connections[i].lastName;
			connections[name] = data.connections[i].id;
			connectionNames[i] = name;
		}

		// init custom search
		$('#custom-keepers')
			.textext({
				plugins : 'tags autocomplete',
				prompt : 'Add...'
			})
			.bind('getSuggestions', function(e, data) {
				var textext = $(e.target).textext()[0];
				var query = data && data.query || '';
				$(this).trigger('setSuggestions', {result: textext.itemManager().filter(connectionNames, query)});
			}).bind('setFormData', function(e, data) {
				doSearch();
			});
		$(".text-core").hide().find(".text-wrap").addBack().height("1.5em");
	});

	$('select[name="keepers"]').change(function() { // execute search when changing the filter
		$('#custom-keepers').val('');
		if (this.value == 'c') {
			$('.text-core').show().find('textarea').focus();
		} else {
			$('.text-core').hide();
			doSearch();
		}
	});

	$(".left-col>.my-keeps>a").click(function() {
		populateMyKeeps();
		addNewKeeps();
	});

	$colls.on("click", "h3.collection>a", function(e) {
		var $a = $(this), $coll = $a.parent();
		if ($coll.hasClass("renaming")) {
			if (e.target === this) {
				$a.find("input").focus();
			}
		} else {
			populateMyKeeps($coll.data("id"));
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
				collections[data.id] = {id: data.id, name: name};
				collTmpl.prepend({id: data.id, name: name, keeps: 0});
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
						if (keepButton.is('.private'))
							keep.find('.bottom span.private').addClass('on');
						else
							keep.find('.bottom span.private').removeClass('on');
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
