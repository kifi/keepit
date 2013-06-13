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
		$('div.search .num-results').text('Showing ' + $results.find('.keep').length + ' for "' + $query.val() + '"');
	});
	var $colls = $("#collections"), collTmpl = Tempo.prepare($colls).when(TempoEvent.Types.RENDER_COMPLETE, function(event) {
		makeCollectionsDroppable($colls.find(".collection"));
		adjustCollHeight();
	});
	var $inColl = $(".in-collections"), inCollTmpl = Tempo.prepare($inColl);

	var me;
	var searchContext;
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
		var $keeps = ui.draggable;
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
					addMyKeepsToCollection.call(this, myKeepIds);
				}
			});
		} else {
			addMyKeepsToCollection.call(this, myKeepIds);
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
		$('.search h1').hide();
		$('.search .num-results').show();
		//$('aside.right').show();
		showLoading();
		$results.show();
		$.getJSON(urlSearch,
			{maxHits: 30
			,f: $('select[name="keepers"]').val() == 'c' ? $('#custom-keepers').textext()[0].tags().tagElements().find('.text-label').map(function(){return connections[$(this).text()]}).get().join('.') : $('select[name="keepers"]').val()
			,q: $query.val()
			,context: context
			},
			function(data) {
				if (data.mayHaveMore)
					searchContext = data.context;
				else
					searchContext = null;
				if (context == null) {
					searchTemplate.render(data.hits);
					hideRightSide();
				} else
					searchTemplate.append(data.hits);
			});
	}

	function addNewKeeps() {
		var first = $myKeeps.find('.keep').first().data('id');
		var params = {after: first};
		if ($('aside.left h3.active').is('.collection'))
			params.collection = $('aside.left h3.active').data('id');
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
			$('aside.left h3.my-keeps').addClass('active');
			$main.find(".search h1").text('Browse your keeps');
		} else {
			$('aside.left h3[data-id="' + id + '"]').addClass('active');
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
		$query.val('');
		searchContext = null;
	//	$('aside.right').hide();
		$('.search h1').show();
		$('.search .num-results').hide();
		if (lastKeep == null) {
			$myKeeps.find('.search-section').remove();
		} else {
			params.before = lastKeep;
		}
		$myKeeps.show();
		if (lastKeep != "end") {
			showLoading();
			console.log("Fetching 30 keep before " + lastKeep);
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
			$('aside.left .my-keeps span').text(data.numKeeps);
		});
	}

	function showMessage(msg) {
		$.fancybox($('<p>').text(msg));
	}

	function adjustCollHeight() {
		var $w = $("#collections-wrapper");
		$w.height($(window).height() - $w.offset().top);
	}

	// delete/rename collection
	$colls.on('click', 'a.remove', function() {
		var colElement = $(this).parents('h3.collection').first();
		var colId = colElement.data('id');
		console.log('Removing collection ' + colId);
		$.ajax( {url: urlCollections + "/" + colId + "/delete"
			,type: "POST"
			,dataType: 'json'
			,data: '{}'
			,contentType: 'application/json'
			,error: function() {showMessage('Could not delete collection, please try again later')}
			,success: function(data) {
							colElement.remove();
							$('aside.right .collections ul li:has(input[data-id="'+colId+'"])').remove();
						}
			});
	}).on('click','a.rename',function() {
		var colElement = $(this).parents('h3.collection').first().addClass('editing');
		var nameSpan = colElement.find('span.name').first();
		var name = nameSpan.text();
		nameSpan.html('<input type="text" value="' + name + '" data-orig="' + name + '"/>');
		nameSpan.find('input').focus();
	}).on('keypress', '.collection span.name input', function(e) {
		if (e.which == 13) { // Enter
			var $c = $(this).closest('h3.collection');
			var colId = $c.data('id');
			console.log('Renaming collection ' + colId);
			var newName = this.value;
			$.ajax({
				url: urlCollections + "/" + colId + "/update",
				type: "POST",
				dataType: 'json',
				data: JSON.stringify({name: newName}),
				contentType: 'application/json',
				error: function() {
					showMessage('Could not rename collection, please try again later');
					var $name = $c.find('.name');
					$name.html($name.find('input').data('orig'));
				},
				success: function(data) {
					$c.removeClass('editing');
					$c.find('.name').html(newName).attr('title', newName);
					adjustCollHeight();
				}
			});

		}
	}).on('blur','.collection span.name input', function() {
		$('h3.collection.editing').removeClass('editing');
		$(this).parent().html($(this).data('orig'));
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
				var $count = $('aside.left .collection[data-id="'+colId+'"]').find('a span.right');
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
				var $count = $('aside.left .collection[data-id="'+colId+'"]').find('a span.right');
				$count.text(+$count.text()  + data.added);
				if (!$inColl.find("#cb1-" + colId).length) {
					inCollTmpl.append({id: colId, name: collections[colId].name});
				}
				$row.remove();
			}});
	});

	$(document).keypress(function(e) {  // auto focus on search field when starting to type anywhere on the document
		if (!$(e.target).is('textarea,input')) {
			$query.focus();
		}
	}).scroll(function() { // infinite scroll
		if (!isLoading() && $(document).height() - ($(window).scrollTop() + $(window).height()) < 300) { //  scrolled down to less than 300px from the bottom
			if (searchContext) {
				doSearch(searchContext);
			} else {
				populateMyKeeps($colls.find(".collection.active").data("id"));
			}
		}
	})

	var $main = $(".main").on('click', '.keep', function(e) {
		var $keep = $(this);
		var $cb = $keep.find('input[type="checkbox"]');
		if (!$(e.target).is('input[type="checkbox"]')) {
			$cb.prop('checked', !$cb.is(':checked'));
		}
		if ($cb.is(':checked')) {
			$keep.addClass('selected');
		} else {
			$keep.removeClass('selected');
		}
		var $selected = $main.find(".keep.selected");
		if ($('.keep input[type="checkbox"]:checked').length == 0) {
			// if no keeps are checked, hide the side bar
			hideRightSide();
		} else if ($selected.length > 1) {
			//  handle multiple selection
			$('aside.right .title h2').text($selected.length + " keeps selected");
			$('aside.right .title a').text('');
			$('aside.right .who-kept').html('');
			var allCol = [];
			$selected.each(function() {
				$('aside.right .who-kept').append('<div class="long-text">' + $(this).find('a').first().text() + '</div>');
				if ($(this).data('collections').length > 0) {
					var colArray = $(this).data('collections').split(',');
					allCol = allCol.concat(colArray);
				}
			});
			allCol = unique(allCol);
			$inColl.empty();
			for (i in allCol) {
				inCollTmpl.append({id: allCol[i], name: collections[allCol[i]].name});
			}
			showRightSide();
		} else { // only one keep is selected
			$keep = $selected.first();
			$('aside.right .title h2').text($keep.find('a').first().text());
			var url = $keep.find('a').first().attr('href');
			$('aside.right .title a').text(url).attr('href',url).attr('target','_blank');
			$('aside.right .who-kept').html($keep.find('div.bottom').html());
			$('aside.right .who-kept span').prependTo($('aside.right .who-kept')).removeClass('fs9 gray');
			$inColl.empty();
			if ($keep.data('collections')) {
				$keep.data('collections').split(',').forEach(function(id) {
					inCollTmpl.append({id: id, name: collections[id].name});
				});
			}
			var keepButton = $('aside.right .keepit .keep-button');
			if ($keep.is('.mine')) {
				keepButton.addClass('kept');
				if ($keep.is('.private')) {
					keepButton.addClass('private');
				} else {
					keepButton.removeClass('private');
				}
				keepButton.find('span.text').text('kept');
			} else {
				keepButton.removeClass('kept private');
				keepButton.find('span.text').text('keep it');
			}
			showRightSide();
		}
	});

	var $query = $("input.query").keyup(function() {
		clearTimeout(searchTimeout);
		searchTimeout = setTimeout(doSearch, 500);
	}).focus(function() {  // instant search
		$('aside.left .active').removeClass('active');
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

	// make collections sortable
	$('aside.left #collections-wrapper').sortable({items: 'h3', opacity: 0.6,
		placeholder: "sortable-placeholder",
		beforeStop: function( event, ui ) {
			// update the collection order
			$.ajax( {url: urlCollectionsOrder
				,type: "POST"
				,async: false
				,dataType: 'json'
				,data: JSON.stringify($('aside.left #collections-wrapper h3').map(function() {return ($(this).data('id'))}).get())
				,contentType: 'application/json'
				,error: function() {showMessage('Could not reorder the collections, please try again later'); return false;}
				,success: function(data) {
								console.log(data);
							}
			});
	}});

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

	$colls.on('click', "h3 a", function() {
		populateMyKeeps($(this).parent().data('id'));
	});

	$('aside a.new-collection').click(function() {
		var $add = $(this).closest('div').find('.add-collection');
		if ($add.is(':visible')) {
			$add.slideUp();
		} else {
			$add.slideDown();
			$add.find('input').focus();
		}
	});

	// filter collections or right bar
	$('aside.right .collections input.find').keyup(function() {
		var re = new RegExp(this.value, "gi");
		$('aside.right .collections ul li:not(.create)').each(function() {
			$(this).toggle(re.test($(this).find('label').text()));
		});
	});

	// create new collection
	$('.add-collection input').keypress(function(e) {
		if (e.which == 13) { // Enter
			var input = this;
			var newName = input.value;
			$.ajax({
				url: urlCollectionsCreate,
				type: "POST",
				dataType: 'json',
				data: JSON.stringify({name: newName}),
				contentType: 'application/json',
				error: showMessage.bind(null, 'Could not create collection, please try again later'),
				success: function(data) {
					var $coll = $('<h3 class=collection data-id=' + data.id + '><div class=edit-menu>\
						<a href=javascript: class=edit></a>\
						<ul><li><a class=rename href=javascript:>Rename</a></li>\
								<li><a class=remove href=javascript:>Remove</a></li></ul>\
						</div><a href=javascript:><span class="name long-text">' + newName + '</span> <span class="right light">0</span></a></h3>')
				   .appendTo('#collections-wrapper');
					makeCollectionsDroppable($coll);
				  // TODO: Use Tempo templates!!
					$('aside.right .actions .collections ul li.create')
						.after('<li><input type="checkbox" data-id="' + data.id + '" id="cb-' + data.id + '"><label class="long-text" for="cb-' + data.id + '"><span></span>' + newName + '</label></li>');
					collections[data.id] = {id: data.id, name: newName};
					$(input).parent().slideUp();
					input.value = "";
				}});
		 }
	});

	$('aside.right .actions a.add').click(function() {
		$(this).toggleClass('active');
		$('aside.right .collections').toggleClass('active');
	})

	$('aside.left').on('mouseenter','h3.collection div.edit-menu',function() {
		$(this).parents('h3').first().addClass('hover');
	}).on('mouseleave','h3.collection div.edit-menu',function() {
		$(this).parents('h3').first().removeClass('hover');
		$('aside.left h3.collection div.edit-menu ul').hide();
	}).on('click','h3.collection div.edit-menu > a',function() {
		$('aside.left h3.collection div.edit-menu ul').toggle();
	});

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
