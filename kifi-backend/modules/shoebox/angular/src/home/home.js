'use strict';

angular.module('kifi')

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/', {
      templateUrl: 'home/home.tpl.html',
      controller: 'HomeCtrl'
    });
  }
])

.controller('HomeCtrl', [
  '$scope', 'tagService', 'keepService', '$q', '$timeout', '$window', 'installService', '$rootScope',
  function ($scope, tagService, keepService, $q, $timeout, $window, installService, $rootScope) {
    keepService.reset();

    $window.document.title = 'Kifi • Your Keeps';

    $scope.keepService = keepService;
    $scope.keeps = keepService.list;
    $scope.enableSearch();
    $scope.hasLoaded = false;

    $scope.hasMore = function () {
      return !keepService.isEnd();
    };

    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Loading...';
      }

      var subtitle = keepService.getSubtitle($scope.mouseoverCheckAll);
      if (subtitle) {
        return subtitle;
      }

      var numShown = $scope.keeps.length;
      switch (numShown) {
      case 0:
        return 'You have no keeps';
      case 1:
        return 'Showing your only keep';
      case 2:
        return 'Showing both of your keeps';
      default:
        if (keepService.isEnd()) {
          return 'Showing all ' + numShown + ' of your keeps';
        }
        return 'Showing your ' + numShown + ' latest keeps';
      }
    };

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return $q.when([]);
      }

      $scope.loading = true;

      return keepService.getList().then(function (list) {
        $scope.loading = false;
        $scope.hasLoaded = true;

        if (keepService.isEnd()) {
          $scope.scrollDisabled = true;
        }

        return list;
      });
    };

    function initKeepList() {
      $scope.scrollDisabled = false;
      $scope.getNextKeeps().then(function () {
        return $scope.getNextKeeps();
      });
    }

    $scope.$watch('keepService.seqReset()', function () {
      initKeepList();
    });

    $scope.showEmptyState = function () {
      return tagService.getTotalKeepCount() === 0;
    };

    $scope.triggerInstall = function () {
      installService.triggerInstall(function () {
        $rootScope.$emit('showGlobalModal', 'installExtensionError');
      });
    };

    $scope.importBookmarks = function () {
      var kifiVersion = $window.document.documentElement.dataset.kifiExt;

      if (!kifiVersion) {
        $rootScope.$emit('showGlobalModal','installExtension');
        return;
      }

      $rootScope.$emit('showGlobalModal', 'importBookmarks');
    };

    $scope.importBookmarkFile = function () {
      $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
    };

    $scope.addKeeps = function () {
      $rootScope.$emit('showGlobalModal', 'addKeeps');
    };
  }
]);
