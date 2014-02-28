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
  '$scope', 'keepService', '$routeParams',
  function ($scope, keepService, $routeParams) {
    keepService.reset();

    var tagId = $routeParams.tagId || '';
    console.log(tagId);

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
        return 'Sorry, no results found for &#x201c;' + query + '&#x202c;';
      case 1:
        return '1 result found';
      default:
        return 'Top ' + numShown + ' results';
      }
    };

    $scope.scrollDistance = '100%';
    $scope.scrollDisabled = false;

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }

      $scope.loading = true;
      keepService.find(query, filter, lastResult && lastResult.context).then(function (data) {
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
