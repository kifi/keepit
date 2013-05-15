var urlSearch = 'https://api.kifi.com/search';
var urlMyKeeps = 'https://api.kifi.com/site/keeps/all';
var urlMyKeepsCount = 'https://api.kifi.com/site/keeps/count';
var urlMe = 'https://api.kifi.com/site/user/me';
var urlConnections = 'https://api.kifi.com/site/user/connections';

var keepsTemplate = Tempo.prepare("my-keeps").when(TempoEvent.Types.RENDER_COMPLETE, function (event) {
				hideLoading();
				$('#my-keeps .keep .bottom').prepend('<img class="small-avatar" src="' + myAvatar + '"/>');

				// insert time sections
				var currentDate = new Date();
				var today = false, yesterday = false, week = false, older = false;
				$('#my-keeps .keep').each(function() {
					var age = daysBetween(new Date(Date.parse($(this).data('created'))), currentDate);
					if (!today && age <= 1) {
						$(this).before('<li class="search-section">Today</li>'); today = true;
					} else if (!yesterday && age > 1 && age <= 2) {
						$(this).before('<li class="search-section">Yesderday</li>'); yesterday = true;
					} else if (!week && age > 2 && age <= 7) {
						$(this).before('<li class="search-section">Past Week</li>'); week = true;
					} else if (!older && age > 7) {
						$(this).before('<li class="search-section">Older</li>'); older = true;
					}
				});
			});
var searchTemplate = Tempo.prepare("search-results").when(TempoEvent.Types.RENDER_COMPLETE, function (event) {
					hideLoading();
					$('#search-results .keep .bottom').each(function() {
						$(this).find('img').prependTo($(this));
					});
					$('#search-results .keep.mine .bottom:not(:has(.me))').prepend('<img class="small-avatar me" src="' + myAvatar + '"/>');
					$('div.search .num-results span').text($('#search-results .keep').length);
				});
var searchContext = null;
var connections = {};
var connectionNames = [];
var myAvatar = '';

$.ajaxSetup({
    xhrFields: {
       withCredentials: true
    },
    crossDomain: true
});

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
	$('#my-keeps .search-section').remove();
	keepsTemplate.clear();
	$('.search h1').hide();
	$('.search .num-results').show();
	$('aside.right').show();
	showLoading();
	$.getJSON(urlSearch, 
		{maxHits: 50
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

function populateMyKeeps() {
	$('.active').removeClass('active');
	$('aside.left h3.my-keeps').addClass('active');
	searchTemplate.clear();
	searchContext = null;
	$('aside.right').hide();
	$('.search h1').show();
	$('.search .num-results').hide();
	$('#my-keeps .search-section').remove();
	showLoading();
	$.getJSON(urlMyKeeps, function(data) {
		keepsTemplate.render(data.keeps);
	});
}

$(document)
	.on('keypress', function(e) {if (!$(e.target).is('textarea')) $('input.search').focus() }) // auto focus on search field when starting to type anywhere on the document
	.on('scroll',function() { // infinite scroll
		if (searchContext != null && !isLoading() && ($(window).scrollTop() + $(window).height())/ $(document).height() > .9) // scrolled down more than %90
			doSearch(searchContext);
	})
	.ready(function() {
		// populate number of my keeps 
		$.getJSON(urlMyKeepsCount, function(data) {
			$('aside.left .my-keeps span').text(data.numKeeps);
		});

		// populate all my keeps
		populateMyKeeps();

		// populate user data 
		$.getJSON(urlMe, function(data) {
			myAvatar = getAvatar(data.id, data.pictureName, 200);
			$('aside.left .large-avatar').html('<img src="' + myAvatar + '"/>\
				<div class="name">' + data.firstName + ' ' + data.lastName + '</div>');
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
			.on('keyup',function() {doSearch(null)}) // instant search
			.on('focus',function() {$('.active').removeClass('active'); $(this).addClass('active')});


	});


