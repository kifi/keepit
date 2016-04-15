'use strict';

angular.module('kifi')

.factory('manageTagService', [
  '$http', '$q', '$state', '$analytics', 'routeService', 'undoService', 'Clutch',
  function ($http, $q, $state, $analytics, routeService, undoService, Clutch) {

    var pageClutch = new Clutch(function (sort, offset) {
      return $http.get(routeService.pageTags(sort, offset, 300)).then(function (res) {
        return res.data.tags;
      });
    });

    var searchClutch = new Clutch(function (query) {
      if (!query || !query.trim()) {
        return $q.when([]);
      }
      return $http.get(routeService.searchTags(query, 100)).then(function (res) {
        return _.map(res.data.results, function (r) {
          return {name: r.tag, keeps: r.keepCount};
        });
      });
    });

    return {
      reset: function () {
        pageClutch.expireAll();
      },
      getMore: function (sort, offset) {
        return pageClutch.get(sort, offset);
      },
      search: function (query) {
        return searchClutch.get(query);
      },
      remove: function (tagName) {
        var promise = $http.post(routeService.deleteTag(tagName));
        $analytics.eventTrack('user_clicked_page', {action: 'removeTag', path: $state.href($state.current)});
        return promise;
      },
      rename: function (oldTagName, newTagName) {
        var promise = $http.post(routeService.renameTag(), {'oldTagName': oldTagName, 'newTagName': newTagName });
        $analytics.eventTrack('user_clicked_page', {action: 'renameTag', path: $state.href($state.current)});
        return promise;
      }
    };

  }
]);
