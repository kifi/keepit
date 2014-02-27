'use strict';

angular.module('kifi.keeps', ['kifi.profileService', 'kifi.keepService', 'kifi.tagService'])

.controller('KeepsCtrl', [
  '$scope', 'profileService', 'keepService', 'tagService',
  function ($scope, profileService, keepService, tagService) {
    $scope.me = profileService.me;

    $scope.$watch(function () {
      return ($scope.keeps && $scope.keeps.length || 0) + ',' + tagService.list.length;
    }, function () {
      // update antiscroll
      $scope.refreshScroll();

      if ($scope.keeps && $scope.keeps.length && tagService.list.length) {
        keepService.joinTags($scope.keeps, tagService.list);
      }
    });
  }
])

.directive('kfKeeps', [
  'keepService',
  function (keepService) {

    return {
      restrict: 'A',
      scope: {
        keeps: '=',
        scrollDistance: '=',
        scrollDisabled: '=',
        scrollNext: '&'
      },
      controller: 'KeepsCtrl',
      templateUrl: 'keeps/keeps.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        keepService.reset();

        scope.select = keepService.select;
        scope.unselect = keepService.unselect;
        scope.toggleSelect = keepService.toggleSelect;
        scope.isSelected = keepService.isSelected;
        scope.preview = keepService.preview;
        scope.togglePreview = keepService.togglePreview;
        scope.isPreviewed = keepService.isPreviewed;

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
          }
          return subtitle.text;
        };

        scope.onClickKeep = function (keep, $event) {
          if ($event.target.tagName !== 'A') {
            scope.togglePreview(keep);
          }
        };

        scope.isScrollDisabled = function () {
          return scope.scrollDisabled;
        };

        if (scope.scrollDistance == null) {
          scope.scrollDistance = '100%';
        }
      }
    };
  }
]);
