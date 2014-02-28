'use strict';

angular.module('kifi.tagKeeps', ['util', 'kifi.keepService'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/tag/:tagId', {
      templateUrl: '/tagKeeps/tagKeeps.tpl.html',
      controller: 'TagKeepsCtrl'
    });
  }
])

.controller('TagKeepsCtrl', [
  '$scope', 'keepService', 'tagService', '$routeParams',
  function ($scope, keepService, tagService, $routeParams) {
    keepService.reset();

    var tagId = $routeParams.tagId || '';
    tagService.promiseById(tagId).then(function (tag) {
      console.log('tag', tagId, tag);
    });

    $scope.keepService = keepService;
    $scope.keeps = keepService.list;

    $scope.toggleSelectAll = keepService.toggleSelectAll;
    $scope.isSelectedAll = keepService.isSelectedAll;

    $scope.checkEnabled = true;
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

    var lastResult = null;

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }

      $scope.loading = true;
      keepService.getKeepsByTag().then(function (data) {
        $scope.loading = false;

        if (keepService.isEnd()) {
          $scope.scrollDisabled = true;
        }

        lastResult = data;
      });
    };

    $scope.getNextKeeps();
  }
]);
