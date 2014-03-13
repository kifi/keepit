'use strict';

angular.module('kifi.tagKeeps', ['util', 'kifi.keepService'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/tag/:tagId', {
      templateUrl: 'tagKeeps/tagKeeps.tpl.html',
      controller: 'TagKeepsCtrl'
    });
  }
])

.controller('TagKeepsCtrl', [
  '$scope', 'keepService', 'tagService', '$routeParams', '$window',
  function ($scope, keepService, tagService, $routeParams, $window) {

    keepService.reset();
    $scope.keepService = keepService;
    $scope.keeps = keepService.list;

    var tagId = $routeParams.tagId || '';

    $scope.toggleSelectAll = keepService.toggleSelectAll;
    $scope.isSelectedAll = keepService.isSelectedAll;

    $scope.isCheckEnabled = function () {
      return $scope.keeps.length;
    };

    $scope.hasMore = function () {
      return !keepService.isEnd();
    };

    $scope.mouseoverCheckAll = false;

    $scope.onMouseoverCheckAll = function () {
      $scope.mouseoverCheckAll = true;
    };

    $scope.onMouseoutCheckAll = function () {
      $scope.mouseoverCheckAll = false;
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
        return 'No Keeps in this tag';
      case 1:
        return 'Showing the only Keep in this tag';
      case 2:
        return 'Showing both Keeps in this tag';
      }
      if (keepService.isEnd()) {
        return 'Showing all ' + numShown + ' Keeps in this tag';
      }
      return 'Showing the ' + numShown + ' latest Keeps in this tag';
    };

    $scope.scrollDistance = '100%';
    $scope.scrollDisabled = false;

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }

      $scope.loading = true;
      return keepService.getKeepsByTagId(tagId).then(function (list) {
        $scope.loading = false;

        if (keepService.isEnd()) {
          $scope.scrollDisabled = true;
        }

        return list;
      });
    };

    $scope.getNextKeeps();

    tagService.promiseById(tagId).then(function (tag) {
      $window.document.title = 'Kifi • ' + tag.name;
      $scope.tag = tag || null;
    });

  }
]);
