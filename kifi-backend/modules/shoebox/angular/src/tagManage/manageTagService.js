'use strict';

angular.module('kifi')

.factory('manageTagService', [
  '$http', '$q', '$state', '$analytics', 'routeService', 'undoService', 'Clutch',
  function ($http, $q, $state, $analytics, routeService, undoService, Clutch) {

    var pageClutch = new Clutch(function (sort, offset) {
      return $http.get(routeService.pageTags(sort, offset, 100)).then(function (res) {
        return res.data.tags;
      });
    });

    var searchClutch = new Clutch(function (query) {
      if (!query || !query.trim()) {
        return $q.when([]);
      }
      return $http.get(routeService.searchTags(query, 30)).then(function (res) {
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
      remove: function (tag) {
        var promise = $http.post(routeService.deleteTag(tag.id)).then(function () {
          undoService.add('Tag deleted.', function () {
            $http.post(routeService.undeleteTag(tag.id)).then(function () {
              $state.go($state.current, undefined, {reload: true});
              $analytics.eventTrack('user_clicked_page', {action: 'unremoveTag', path: $state.href($state.current)});
            });
         });
        });
        $analytics.eventTrack('user_clicked_page', {action: 'removeTag', path: $state.href($state.current)});
        return promise;
      }
    };

  }
]);
