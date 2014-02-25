'use strict';

angular.module('kifi.keeps', ['kifi.profileService', 'kifi.keepService', 'kifi.tagService'])

.controller('KeepsCtrl', [
  '$scope', 'profileService', 'keepService', 'tagService', '$q',
  function ($scope, profileService, keepService, tagService, $q) {
    $scope.me = profileService.me;
    $scope.keeps = keepService.list;

    $scope.$watch('keeps.length', function () {
      $scope.refreshScroll();
    });

    $scope.loadingKeeps = true;
    var promise = keepService.getList().then(function (list) {
      $scope.loadingKeeps = false;
      return list;
    });

    $q.all([promise, tagService.fetchAll()]).then(function () {
      $scope.loadingKeeps = false;
      $scope.refreshScroll();
      keepService.joinTags(keepService.list, tagService.list);
    });

    $scope.getNextKeeps = function () {
      if ($scope.loadingKeeps) {
        return $q.when([]);
      }

      $scope.loadingKeeps = true;

      return keepService.getList().then(function (list) {
        $scope.loadingKeeps = false;
        $scope.refreshScroll();
        return list;
      });
    };

    $scope.selectKeep = keepService.select;
    $scope.unselectKeep = keepService.unselect;
    $scope.isSelectedKeep = keepService.isSelected;
    $scope.toggleSelectKeep = keepService.toggleSelect;

    $scope.toggleSelectAll = keepService.toggleSelectAll;
    $scope.isSelectedAll = keepService.isSelectedAll;

    $scope.isPreviewedKeep = keepService.isPreviewed;
    $scope.togglePreviewKeep = keepService.togglePreview;
  }
])

.directive('kfKeeps', [

  function () {
    return {
      restrict: 'A',
      scope: {},
      controller: 'KeepsCtrl',
      templateUrl: 'keeps/keeps.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        scope.checkEnabled = true;

        scope.getSubtitle = function () {
          var subtitle = scope.subtitle;
          var numShown = scope.results.numShown;
          switch (subtitle.type) {
          case 'tag':
            switch (numShown) {
            case 0:
              return 'No Keeps in this tag';
            case 1:
              return 'Showing the only Keep in this tag';
            case 2:
              return 'Showing both Keeps in this tag';
            }
            if (numShown === scope.results.numTotal) {
              return 'Showing all ' + numShown + ' Keeps in this tag';
            }
            return 'Showing the ' + numShown + ' latest Keeps in this tag';
          case 'keeps':
            switch (numShown) {
            case 0:
              return 'You have no Keeps';
            case 1:
              return 'Showing your only Keep';
            case 2:
              return 'Showing both of your Keeps';
            }
            if (numShown === scope.results.numTotal) {
              return 'Showing all ' + numShown + ' of your Keeps';
            }
            return 'Showing your ' + numShown + ' latest Keeps';
          }
          return subtitle.text;
        };

        scope.setLoading = function () {
          scope.subtitle = {
            text: 'Loading...'
          };
        };

        scope.setLoading();

        scope.togglePreview = function (keep, $event) {
          if ($event.target.tagName !== 'A') {
            scope.togglePreviewKeep(keep);
          }
        };

        scope.onScrollNext = function () {
          scope.getNextKeeps();
        };

        scope.isScrollDisabled = function () {
          return scope.loadingKeeps;
        };

        scope.scrollDistance = '100%';
      }
    };
  }
]);
