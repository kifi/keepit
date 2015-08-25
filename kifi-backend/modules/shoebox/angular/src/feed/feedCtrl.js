'use strict';

angular.module('kifi')

.controller('FeedCtrl', [
  '$scope', 'feedService', 'Paginator', function($scope, feedService, Paginator) {
    function keepSource(pageNumber, pageSize) {
      var lastKeep = $scope.feed[(pageNumber * pageSize) - 1] || $scope.feed[$scope.feed.length - 1];

      return feedService.getFeed(pageSize, lastKeep && lastKeep.id)
      .then(function(keepData) {
        return keepData.keeps;
      });
    }

    var keepLazyLoader = new Paginator(keepSource, 10);

    $scope.feed = [];

    $scope.scrollDistance = '100%';
    $scope.hasMoreKeeps = function () {
      return keepLazyLoader.hasMore();
    };
    $scope.isLoading = function () {
      return !keepLazyLoader.hasLoaded();
    };
    $scope.fetchKeeps = function () {
      keepLazyLoader
      .fetch()
      .then(function (keeps) {
        $scope.feed = keeps;
      });
    };
  }
]);
