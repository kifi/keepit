'use strict';

angular.module('kifi')

.controller('FeedCtrl', [
  '$rootScope', '$scope', 'feedService', 'Paginator', 'modalService',
  function($rootScope, $scope, feedService, Paginator, modalService) {
    function keepSource(pageNumber, pageSize) {
      var lastKeep = $scope.feed[(pageNumber * pageSize) - 1] || $scope.feed[$scope.feed.length - 1];

      return feedService.getFeed(pageSize, lastKeep && lastKeep.id)
      .then(function(keepData) {
        return keepData.keeps;
      });
    }

    var keepLazyLoader = new Paginator(keepSource, 10);

    $scope.feed = [];

    $scope.scrollDistance = '0';
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
      })
      ['catch'](modalService.openGenericErrorModal);
    };

    $scope.addKeeps = function () {
      modalService.open({
        template: 'keeps/addKeepModal.tpl.html',
        modalData: {}
      });
    };

    $scope.$on('keepRemoved', function (e, keepData) {
      var url = keepData.url;

      var matches = $scope.feed.filter(function (f) {
        return f.url === url;
      });
      var removedFeedItem = matches && matches[0];
      var removedIndex = $scope.feed.indexOf(removedFeedItem);

      if (removedIndex !== -1) {
        $scope.feed.splice(removedIndex, 1);
      }
    });

    [
      $rootScope.$on('keepAdded', function (e, keeps) {
        $scope.feed.push(keeps[0]);
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });

    $scope.suggestedSearches = [
      { title: 'Startups', query: 'startups' },
      { title: 'Productivity', query: 'productivity' },
      { title: 'Developer', query: 'developer' },
      { title: 'Collaboration', query: 'collaboration' },
      { title: 'Creativity', query: 'creativity' },
      { title: 'Coffee', query: 'coffee' },
      { title: 'Recruiting', query: 'recruiting' },
      { title: 'Data Science', query: 'data%20science' },
      { title: 'Brain Development', query: '%22brain%20development%22' },
      { title: 'Apps', query: 'apps' }
    ];

    // Initialize
    $scope.fetchKeeps();
  }
]);
