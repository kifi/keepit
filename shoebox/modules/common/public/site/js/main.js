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
					initDraggable();
					$('#search-results .keep .bottom').each(function() {
						$(this).find('img.small-avatar').prependTo($(this));
					});
					$('#search-results .keep.mine .bottom:not(:has(.me))').prepend('<img class="small-avatar me" src="' + myAvatar + '"/>');
					$('div.search .num-results').text('Showing ' + $('#search-results .keep').length + ' for "'+$('header input.search').val()+'"');
				});
var collectionsTemplate = Tempo.prepare("collections").when(TempoEvent.Types.RENDER_COMPLETE, function (event) {
	$('#collections').show();
	initDroppable();
	adjustHeight();
});

var searchContext = null;
var connections = {};
var collections = {};
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

function unique(arr) {
    return $.grep(arr,function(v,k){
        return $.inArray(v,arr) === k;
    });
}

function initDraggable() {
	$( ".draggable" ).draggable({ 
		revert: "invalid",
		handle: ".handle",
		cursorAt: { top: 15, left: 0 },
		helper: function() {
			var text = $(this).find('a').first().text();
			var numSelected = $('section.main .keep.selected').length; 
			if (numSelected > 1)
				text = numSelected + " selected keeps";
			return $('<div class="drag-helper">').html(text);
		} 
	});	
}

function initDroppable() {
	$( ".droppable" ).droppable({
		accept: '.keep',
		greedy: true,
		tolerance: "pointer",
		hoverClass: "drop-hover",
		drop: function( event, ui ) {
			var thisCollection = $(this);
			var collectionId = thisCollection.data('id');

			// first add keeps that are not mine 
			var keeps = $('section.main .keep.selected:not(.mine)');
			if (keeps.length > 0)
				$.ajax( {url: urlKeepAdd
					,type: "POST"
					,dataType: 'json'
					,data: JSON.stringify(keeps.map(function() {return { title: $(this).find('a').first().attr('title')
						,url: $(this).find('a').first().attr('href')}}).get())
					,contentType: 'application/json'
					,error: function() {showMessage('Could not add keeps, please try again later')}
					,success: function(data) {
						// add the returned IDs to the collection
						var keepIds = [];
						for (i in data.keeps) {
							keepIds[i] = data.keeps[i].id;
						}
						$.ajax( {url: urlCollections + '/' + collectionId + '/addKeeps' 
							,type: "POST"
							,dataType: 'json'
							,data: JSON.stringify(keepIds)
							,contentType: 'application/json'
							,error: function() {
								showMessage('Could not add to collection, please try again later');
								return false;
							}
							,success: function(data) {
											var countSpan = thisCollection.find('a span.right'); 
											var added = countSpan.text() * 1  + data.added;
											countSpan.text(added);
											// update collection list on right bar
											$('aside.right .in-collections').append('<div class="row"><input type="checkbox" data-id="'+collectionId+'" id="cb1-'+collectionId+'" checked=""><label for="cb1-'+collectionId+'"><span></span>'+collections[collectionId].name+'</label><div></div></div>');
										}
							});						
					}
				});

			// now add my keeps to the collection
			keeps = $('section.main .keep.selected.mine');
			if (keeps.length > 0)
				$.ajax( {url: urlCollections + '/' + collectionId + '/addKeeps' 
					,type: "POST"
					,dataType: 'json'
					,data: JSON.stringify(keeps.map(function() {return $(this).data('id')}).get())
					,contentType: 'application/json'
					,error: function() {
						showMessage('Could not add to collection, please try again later');
						return false;
					}
					,success: function(data) {
									var countSpan = thisCollection.find('a span.right'); 
									var added = countSpan.text() * 1  + data.added;
									countSpan.text(added);
									// update collection list on right bar
									$('aside.right .in-collections').append('<div class="row"><input type="checkbox" data-id="'+collectionId+'" id="cb1-'+collectionId+'" checked=""><label for="cb1-'+collectionId+'"><span></span>'+collections[collectionId].name+'</label><div></div></div>');
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
	$('#my-keeps .keep.selected input[type="checkbox"]').prop('checked', false);
	$('#my-keeps .keep.selected').removeClass('selected');
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
			if (context == null) {
				searchTemplate.render(data.hits);
				$('aside.right').removeClass('visible');
			} else
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
	if (id == null) {
		$('aside.left h3.my-keeps').addClass('active');
		$('section.main .search h1').text('Browse your keeps');
	} else {
		$('aside.left h3[data-id="' + id + '"]').addClass('active');
		params.collection = id;
		$('section.main .search h1').text('Browse your ' + collections[id].name + ' collection');
	}
	if (prevCollection != id) { // reset search if not fetching the same collection
		prevCollection = id;
		lastKeep = null;
		keepsTemplate.clear();
		$('aside.right').removeClass('visible');
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
				$('aside.right .actions .collections ul li:not(.create)').remove();
				for (i in data.collections) {
					collections[data.collections[i].id] = data.collections[i];
					$('aside.right .actions .collections ul').append('<li><input type="checkbox" data-id="'+data.collections[i].id+'" id="cb-'+data.collections[i].id+'"/><label for="cb-'+data.collections[i].id+'"><span></span>'+data.collections[i].name+'</label></li>');
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

function adjustHeight() {
	$('aside.left #collections #collections-wrapper').height($(window).height() - $('aside.left #collections #collections-wrapper').offset().top);
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
						$('aside.right .collections ul li:has(input[data-id="'+colId+'"])').remove();
					}
		});
}).on('click','a.rename',function() {
	var colElement = $(this).parents('h3.collection').first().addClass('editing');
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
							colElement.removeClass('editing');
							colElement.find('span.name').html(newName).attr('title',newName);
							adjustHeight();
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

$(window).resize(adjustHeight);

// handle collection adding/removing from right bar
$('aside.right div.in-collections').on('change','input[type="checkbox"]',function(){
	// remove selected keeps from collection
	var row = $(this).parents('.row');
	var colId = $(this).data('id');
	var keeps = $('section.main .keep.selected').map(function(){ return $(this).data('id')}).get();
	$.ajax( {url: urlCollections + "/" + colId + "/removeKeeps"
		,type: "POST"
		,dataType: 'json'
		,data: JSON.stringify(keeps)
		,contentType: 'application/json'
		,error: function() {showMessage('Could not remove keeps from collection, please try again later')}
		,success: function(data) {
						console.log(data);
						// substract removed from collection count on left bar 
						var countSpan = $('aside.left .collection[data-id="'+colId+'"]').find('a span.right'); 
						countSpan.text(countSpan.text() * 1  - data.removed);
						row.remove();
					}
		});
	
});
$('aside.right .actions .collections').on('change','input[type="checkbox"]',function(){
	// add selected keeps to collection
	var row = $(this).parents('li');
	var colId = $(this).data('id');
	var keeps = $('section.main .keep.selected').map(function(){ return $(this).data('id')}).get();
	$.ajax( {url: urlCollections + "/" + colId + "/addKeeps"
		,type: "POST"
		,dataType: 'json'
		,data: JSON.stringify(keeps)
		,contentType: 'application/json'
		,error: function() {showMessage('Could not add keeps to collection, please try again later')}
		,success: function(data) {
						console.log(data);
						// add to collection count on left bar 
						var countSpan = $('aside.left .collection[data-id="'+colId+'"]').find('a span.right'); 
						countSpan.text(countSpan.text() * 1  + data.added);
						$('aside.right .in-collections').append('<div class="row"><input type="checkbox" data-id="'+colId+'" id="cb1-'+colId+'" checked/><label for="cb1-'+colId+'"><span></span>'+collections[colId].name+'</label><div>');
						row.remove();
					}
		});
	
});

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
	.on('click','.keep',function(e) {
		var keep = $(this);
		var cb = keep.find('input[type="checkbox"]');
		if (!$(e.target).is('input[type="checkbox"]')) {
			if (cb.is(':checked'))
				cb.prop('checked',false);
			else
				cb.prop('checked',true);			
		}
		if (cb.is(':checked')) { 
			keep.addClass('selected');
		} else {
			keep.removeClass('selected');			
		}
		var selected = $('.keep.selected');
		if ($('.keep input[type="checkbox"]:checked').length == 0) {
			// if no keeps are checked, hide the side bar
			$('aside.right').removeClass('visible');
		} else if (selected.length > 1) {
			//  handle multiple selection
			$('aside.right .title h2').text(selected.length + " keeps selected");
			$('aside.right .title a').text('');
			$('aside.right .who-kept').html('');
			var allCol = [];
			selected.each(function() {
				$('aside.right .who-kept').append('<div class="long-text">' + $(this).find('a').first().text() + '</div>');
				if ($(this).data('collections').length > 0) {
					var colArray = $(this).data('collections').split(',');
					allCol = allCol.concat(colArray);
				}
			});
			allCol = unique(allCol);
			var inCol = $('aside.right .in-collections').html('');
			for (i in allCol) {
				inCol.append('<div class="row"><input type="checkbox" data-id="'+allCol[i]+'" id="cb1-'+allCol[i]+'" checked/><label for="cb1-'+allCol[i]+'"><span></span>'+collections[allCol[i]].name+'</label><div>');
			}
			$('aside.right').addClass('visible');
		} else { // only one keep is selcted
			keep = $('.keep.selected').first();
			$('aside.right .title h2').text(keep.find('a').first().text());
			var url = keep.find('a').first().attr('href');
			$('aside.right .title a').text(url).attr('href',url).attr('target','_blank');
			$('aside.right .who-kept').html(keep.find('div.bottom').html());
			$('aside.right .who-kept span').prependTo($('aside.right .who-kept')).removeClass('fs9 gray');
			var inCol = $('aside.right .in-collections').html('');
			if (keep.data('collections').length > 0) {
				var colArray = keep.data('collections').split(',');
				for (i in colArray) {
					inCol.append('<div class="row"><input type="checkbox" data-id="'+colArray[i]+'" id="cb1-'+colArray[i]+'" checked/><label for="cb1-'+colArray[i]+'"><span></span>'+collections[colArray[i]].name+'</label></div>');
				}
			}
			var keepButton = $('aside.right .keepit .keep-button');
			if (keep.is('.mine')) {
				keepButton.addClass('kept');
				if (keep.is('.private')) {
					keepButton.addClass('private');
				} else {
					keepButton.removeClass('private');
				}
				keepButton.find('span.text').text('kept');
			} else {
				keepButton.removeClass('kept private');
				keepButton.find('span.text').text('keep it');
			}
			$('aside.right').addClass('visible');
		} 
	})
	.ready(function() {		
		$(".fancybox").fancybox();
						
		populateCollections();
		
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
		
		$('input.search')
			.on('keyup',function() {
					clearTimeout(searchTimeout);
					searchTimeout = setTimeout('doSearch(null)', 500);
				}) // instant search
			.on('focus',function() {
				$('aside.left .active').removeClass('active'); 
			});


		$('#collections').on('click', 'h3 a', function() {
			populateMyKeeps($(this).parent().data('id'));
		});

		$('aside a.new-collection').click(function() {
			var addColDiv = $(this).parents('div').first().find('.add-collection');
			if (addColDiv.is(':visible')) {
				addColDiv.slideUp();
			} else {
				addColDiv.slideDown();
				addColDiv.find('input').focus();				
			}
		});
		
		// filter collections or right bar
		$('aside.right .collections input.find').on('keyup',function() {
			var p = new RegExp($(this).val(),"gi");
			$('aside.right .collections ul li:not(.create)').each(function() {
				if (p.test($(this).find('label').text()))
					$(this).show();
				else
					$(this).hide();
			});
		});

		// create new collection
		$('.add-collection input').keypress(function(e) {
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
									   $('#collections-wrapper').append('<h3 class="droppable collection" data-id="' + data.id + '"><div class="edit-menu">\
												<a href="javascript: ;" class="edit"></a>\
												<ul><li><a class="rename" href="javascript: ;">Rename</a></li>\
													<li><a class="remove" href="javascript: ;">Remove</a></li></ul>\
											</div><a href="javascript: ;"><span class="name long-text">' + newName + '</span> <span class="right light">0</span></a></h3>');
										$('aside.right .actions .collections ul li.create').after('<li><input type="checkbox" data-id="' + data.id + '" id="cb-' + data.id + '"><label for="cb-' + data.id + '"><span></span>' + newName + '</label></li>');
										collections[data.id] = {id: data.id, name: newName};
										initDroppable();
										inputField.parent().slideUp();
										inputField.val('');
									}
						});
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
		});
		
		// keep / unkeep
		$('aside.right .keepit .keep-button').click(function(e) {
			var keepButton = $(this);
			var keeps = $('section.main .keep.selected');
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


