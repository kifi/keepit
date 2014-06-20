'use strict';

angular.module('kifi.searchService', [
	'kifi.routeService',
	'kifi.keepService'
])

.factory('searchService', [
	'$http', '$log', '$location', 'routeService','keepService',
	function($http, $log, $location, routeService, keepService) {
	  return {
	    trackSearchResultClick: function (keep) {
		  var url = routeService.searchResultClicked;
		  var lastSearchContext = keepService.lastSearchContext();
		  if (lastSearchContext && lastSearchContext.query) {
		    var origin = $location.$$protocol + '://' + $location.$$host;
		    if ($location.$$port) {
		      origin = origin + ':' + $location.$$port;
		    }
		    var keeps = keepService.list;
		    var resultPosition = keeps.indexOf(keep);
		    var matches = keep.bookmark.matches || (keep.bookmark.matches = {});
		    var hitContext = {
		      isMyBookmark: keep.isMyBookmark,
		      isPrivate: keep.isPrivate,
		      count: keeps.length,
		      keepers: keep.keepers.map(function(elem) { return elem.id; }),
		      tags: keep.tags,
		      title: keep.bookmark.title,
		      titleMatches: (matches.title || []).length,
		      urlMatches: (matches.url || []).length
		    };
		    var data = {
		      origin: origin,
		      uuid: lastSearchContext.uuid,
		      experimentId: lastSearchContext.experimentId,
		      query: lastSearchContext.query,
		      filter: lastSearchContext.filter,
		      maxResults: lastSearchContext.maxResults,
		      kifiExpanded: true,
		      kifiResults: keeps.length,
		      kifiTime: lastSearchContext.kifiTime,
		      kifiShownTime: lastSearchContext.kifiShownTime,
		      kifiResultsClicked: lastSearchContext.clicks,
		      refinements: keepService.refinements,
		      pageSession: lastSearchContext.pageSession,
		      resultPosition: resultPosition,
		      resultUrl: keep.url,
		      hit: hitContext
		    };
		    $log.log('keepService.trackSearchResultClick() data:', data);
		    $http.post(url, data).then(function (res) {
		      $log.log('keepService.trackSearchResultClick() res:', res);
		    });
		  } else {
		    $log.log('no search context to log');
		  }
		}
	  };
	}
]);