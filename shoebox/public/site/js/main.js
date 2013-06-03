var urlBase = 'https://api.kifi.com';
//var urlBase = 'http://dev.ezkeep.com:9000';
var urlSite = urlBase + '/site'; 
var urlSearch = urlBase + '/search';
var urlKeeps =  urlSite + '/keeps';
var urlMyKeeps = urlKeeps + '/all';
var urlMyKeepsCount = urlKeeps + '/count';
var urlUser = urlSite + '/user';
var urlMe = urlUser + '/me';
var urlConnections = urlUser + '/connections';
var urlCollections = urlSite + '/collections';
var urlCollectionsAll = urlCollections + '/all';
var urlCollectionsCreate = urlCollections + '/create';

var keepsTemplate = Tempo.prepare("my-keeps").when(TempoEvent.Types.RENDER_COMPLETE, function (event) {
				hideLoading();
				$('#my-keeps .keep .bottom').each(function() {
					$(this).find('img.small-avatar').prependTo($(this));
				});
				$('#my-keeps .keep .bottom:not(:has(.me))').prepend('<img class="small-avatar me" src="' + myAvatar + '"/>');
				initDraggable();
				$(".easydate").easydate({set_title: false}); 

				// insert time sections
				var currentDate = new Date();
				$('#my-keeps .keep').each(function() {
					var age = daysBetween(new Date(Date.parse($(this).data('created'))), currentDate);
					if ($('#my-keeps li.search-section.today').length == 0 && age <= 1) {
						$(this).before('<li class="search-section today">Today</li>'); 
					} else if ($('#my-keeps li.search-section.yesterday').length == 0  && age > 1 && age < 2) {
						$(this).before('<li class="search-section yesterday">Yesderday</li>'); 
					} else if ($('#my-keeps li.search-section.week').length == 0  && age >= 2 && age <= 7) {
						$(this).before('<li class="search-section week">Past Week</li>'); 
					} else if ($('#my-keeps li.search-section.older').length == 0  && age > 7) {
						$(this).before('<li class="search-section older">Older</li>'); 
					}
				});
			});
var searchTemplate = Tempo.prepare("search-results").when(TempoEvent.Types.RENDER_COMPLETE, function (event) {
					hideLoading();
					$('#search-results .keep .bottom').each(function() {
						$(this).find('img.small-avatar').prependTo($(this));
					});
					$('#search-results .keep.mine .bottom:not(:has(.me))').prepend('<img class="small-avatar me" src="' + myAvatar + '"/>');
					$('div.search .num-results span').text($('#search-results .keep').length);
				});
var collectionsTemplate = Tempo.prepare("collections").when(TempoEvent.Types.RENDER_COMPLETE, function (event) {
	$('#collections').show();
	initDroppable();
});

var searchContext = null;
var connections = {};
var connectionNames = [];
var myAvatar = '';
var searchTimeout;
var lastKeep= null;
var prevCollection = null;

$.ajaxSetup({
    xhrFields: {
       withCredentials: true
    },
    crossDomain: true
});

function initDraggable() {
	$( ".draggable" ).draggable({ 
		revert: "invalid",
		cursorAt: { top: 15, left: 0 },
		helper: function() {
			return $('<div class="drag-helper">').html($(this).find('a').first().text());
		} 
	});	
}

function initDroppable() {
	$( ".droppable" ).droppable({
		greedy: true,
		tolerance: "pointer",
		hoverClass: "drop-hover",
		drop: function( event, ui ) {
				var thisCollection = $(this);
				var collectionId = thisCollection.data('id');
				$.ajax( {url: urlCollections + '/' + collectionId + '/addKeeps' 
					,type: "POST"
					,dataType: 'json'
					,data: JSON.stringify([ui.draggable.data('id')])
					,contentType: 'application/json'
					,error: function() {
						showMessage('Could not add to collection, please try again later');
						return false;
					}
					,success: function(data) {
									var countSpan = thisCollection.find('a span.right'); 
									var added = countSpan.text() * 1  + data.added;
									countSpan.text(added);
								}
					});
			}
		});
}

function daysBetween(date1, date2) {

    // The number of milliseconds in one day
    var ONE_DAY = 1000 * 60 * 60 * 24

    // Convert both dates to milliseconds
    var date1_ms = date1.getTime()
    var date2_ms = date2.getTime()

    // Calculate the difference in milliseconds
    var difference_ms = Math.abs(date1_ms - date2_ms)

    // Convert back to days and return
    return Math.round(difference_ms/ONE_DAY)

}

function getAvatar(userId, pictureName, size) {
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

function doSearch(context) {
	$('#my-keeps').hide();
	$('.search h1').hide();
	$('.search .num-results').show();
//	$('aside.right').show();
	showLoading();
	$('#search-results').show();
	$.getJSON(urlSearch, 
		{maxHits: 30
		,f: $('select[name="keepers"]').val() == 'c' ? $('#custom-keepers').textext()[0].tags().tagElements().find('.text-label').map(function(){return connections[$(this).text()]}).get().join('.') : $('select[name="keepers"]').val()
		,q: $('input.search').val()
		,context: context
		},
		function(data) {
			if (data.mayHaveMore)
				searchContext = data.context;
			else
				searchContext = null;
			if (context == null)
				searchTemplate.render(data.hits);
			else
				searchTemplate.append(data.hits);
		});
}

function addNewKeeps() {
	var first = $('#my-keeps li.keep').first().data('id');
	var params = {after: first};
	if ($('aside.left h3.active').is('.collection'))
		params.collection = $('aside.left h3.active').data('id');
	console.log("Fetching 30 keep after " + first);
	$.getJSON(urlMyKeeps, params, 
		function(data) {
			keepsTemplate.prepend(data.keeps);
			$('#my-keeps li.search-section.today').prependTo('#my-keeps');
		});
}

function populateMyKeeps(id) {
	var params = {count: 30};
	$('.active').removeClass('active');
	if (id == null) 
		$('aside.left h3.my-keeps').addClass('active');
	else {
		$('aside.left h3[data-id="' + id + '"]').addClass('active');
		params.collection = id;
	}
	if (prevCollection != id) { // reset search if not fetching the same collection
		prevCollection = id;
		lastKeep = null;
		keepsTemplate.clear();
	}
	searchTemplate.clear();
	$('input.search').val('');
	searchContext = null;
//	$('aside.right').hide();
	$('.search h1').show();
	$('.search .num-results').hide();
	if (lastKeep == null) {
		$('#my-keeps .search-section').remove();
	} else {
		params.before = lastKeep;
	}
	$('#my-keeps').show();
	if (lastKeep != "end") {
		showLoading();
		console.log("Fetching 30 keep before " + lastKeep);
		$.getJSON(urlMyKeeps, params, 
			function(data) {
				if (data.keeps.length == 0) { // end of results
					lastKeep = "end"; hideLoading(); return true;
				} else if (lastKeep == null) {
					keepsTemplate.render(data.keeps);				
				} else {
					keepsTemplate.append(data.keeps);				
				}
				lastKeep = data.keeps[data.keeps.length - 1].id;
			});
	}
}

function populateCollections() {
	$.getJSON(urlCollectionsAll,  
			function(data) {
				collectionsTemplate.render(data.collections);	
/*				for (i in data.collections) {
					$('aside.right select[name="collection"]').append('<option value="'+ data.collections[i].id +'">'+ data.collections[i].name +'</option>');
				}
*/			});
}

function updateNumKeeps() {
	$.getJSON(urlMyKeepsCount, function(data) {
		$('aside.left .my-keeps span').text(data.numKeeps);
	});	
}

function showMessage(msg) {
	$.fancybox($('<p>').text(msg));
}

// delete/rename collection
$('#collections').on('click','a.remove',function() {
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
					}
		});
}).on('click','a.rename',function() {
	var colElement = $(this).parents('h3.collection').first();
	var nameSpan = colElement.find('span.name').first();
	var name = nameSpan.text();
	nameSpan.html('<input type="text" value="' + name + '" data-orig="' + name + '"/>');
	nameSpan.find('input').focus();
}).on('keypress','.collection span.name input', function(e) {
	var code = (e.keyCode ? e.keyCode : e.which);
	if(code == 13) { //Enter key pressed
		var colElement = $(this).parents('h3.collection').first();
		var colId = colElement.data('id');
		console.log('Renaming collection ' + colId);
		newName = $(this).val();
		$.ajax( {url: urlCollections + "/" + colId + "/update"
			,type: "POST"
			,dataType: 'json'
			,data: JSON.stringify({name: newName})
			,contentType: 'application/json'
			,error: function() {
				showMessage('Could not rename collection, please try again later');
				var nameSpan = colElement.find('span.name');
				nameSpan.html(nameSpan.find('input').data('orig'));
			}
			,success: function(data) {
							colElement.find('span.name').html(newName);
						}
		});
		
	}
}).on('blur','.collection span.name input', function() {
	$(this).parent().html($(this).data('orig'));
});



// auto update my keeps every minute
setInterval(addNewKeeps, 60000);
setInterval(updateNumKeeps, 60000);
	
$(document)
	.on('keypress', function(e) {if (!$(e.target).is('textarea, input')) $('input.search').focus() }) // auto focus on search field when starting to type anywhere on the document
	.on('scroll',function() { // infinite scroll
		if (!isLoading() && $(document).height() - ($(window).scrollTop() + $(window).height()) < 300) //  scrolled down to less than 300px from the bottom
		{
			if (searchContext != null ) 
				doSearch(searchContext);	
			else if ($('#collections .collection.active').length > 0)
				populateMyKeeps($('#collections .collection.active').data('id'));
			else
				populateMyKeeps();
		}
	})
	.on('click','.keep input[type="checkbox"]',function() {
		if ($(this).is(':checked')) {
			var keep = $(this).parents('.keep').first();
			$('aside.right .title h2').text(keep.find('a').first().text());
			var url = keep.find('a').first().attr('href');
			$('aside.right .title a').text(url).attr('href',url).attr('target','_blank');
			$('aside.right .who-kept').html(keep.find('div.bottom').html());
			$('aside.right .who-kept span').prependTo($('aside.right .who-kept')).removeClass('fs9 gray');
			$('aside.right').addClass('visible');
			$('.keep input[type="checkbox"]').removeAttr('checked');
			$(this).prop('checked', true);
		} else {
			$('aside.right').removeClass('visible');
		}
	})
	.ready(function() {		
		$(".fancybox").fancybox();
		
		populateCollections();
		
		// populate number of my keeps
		updateNumKeeps();

		// populate all my keeps
		populateMyKeeps();

		// populate user data 
		$.getJSON(urlMe, function(data) {
			myAvatar = getAvatar(data.id, data.pictureName, 200);
			$('aside.left .large-avatar').html('<div class="name">' + data.firstName + ' ' + data.lastName + '</div>');
			$('header .header-left').prepend('<img id="my-avatar" src="' + myAvatar + '"/>');
		}); 
		
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
				.bind('getSuggestions', function(e, data)
				{
				    var textext = $(e.target).textext()[0],
					query = (data ? data.query : '') || ''
					;

				    $(this).trigger(
					'setSuggestions',
					{ result : textext.itemManager().filter(connectionNames, query) }
				    );
				}).bind('setFormData', function(e, data) { 
					doSearch();
				});
			$('.text-core').hide();
			$('.text-core, .text-core .text-wrap').height('1.5em');
		}); 

		$('select[name="keepers"]').on('change',function() { // execute search when changing the filter
			$('#custom-keepers').val(''); 
			if ($(this).val() == 'c') 
				$('.text-core').show().find('textarea').focus();
			else {
				$('.text-core').hide();
				doSearch(null); 
			}
		});
/*		$('select[name="collection"]').on('change', function() { // mark selected collection as active
			$('aside.left h3.collection').removeClass('active');
			$('aside.left h3[data-id="'+ $(this).val() +'"]').addClass('active');
		});
*/		
		$('aside.right select[name!="keepers"]').on('change',function() { // execute search when changing the filter
			doSearch(null);
		});
		
		$('input.search')
			.on('keyup',function() {
					clearTimeout(searchTimeout);
					searchTimeout = setTimeout('doSearch(null)', 500);
				}) // instant search
			.on('focus',function() {
/*				if ($('aside.left h3.active').is('.collection')) { // set the search filters to the selected collection
					$('select[name="keepers"]').val('m');
					$('select[name="collection"]').val($('aside.left h3.active').data('id'));
				} else { // reset filters
					$('select[name="keepers"]').val('f');
					$('select[name="collection"]').val('');
				}
*/
				$('aside.left .active').removeClass('active'); 
			});


		$('#collections').on('click', 'h3 a', function() {
			populateMyKeeps($(this).parent().data('id'));
		});

		$('aside.left a#new-collection').click(function() {
			if ($('#add-collection').is(':visible')) {
				$('#add-collection').slideUp();
			} else {
				$('#add-collection').slideDown();
				$('#add-collection input').focus();				
			}
		})

		// create new collection
		$('#add-collection input').keypress(function(e) {
			var code = (e.keyCode ? e.keyCode : e.which);
			if(code == 13) { //Enter key pressed
				var inputField = $(this);
				var newName = inputField.val();
				$.ajax( {url: urlCollectionsCreate
						,type: "POST"
						,dataType: 'json'
						,data: JSON.stringify({ name: newName })
						,contentType: 'application/json'
						,error: function() {showMessage('Could not create collection, please try again later')}
						,success: function(data) {
										console.log(data)
									   $('#collections').append('<h3 class="droppable collection" data-id="' + data.id + '"><div class="edit-menu">\
												<a href="javascript: ;" class="edit"></a>\
												<ul><li><a class="rename" href="javascript: ;">Rename</a></li>\
													<li><a class="remove" href="javascript: ;">Remove</a></li></ul>\
											</div><a href="javascript: ;"><span class="name">' + newName + '</span> <span class="right light">0</span></a></h3>');
									   initDroppable();
									   $('#add-collection').slideUp();
									   inputField.val('');
									}
						});
			 }
		});
	});


