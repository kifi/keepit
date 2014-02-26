'use strict';

angular.module('kifi.keeps', ['kifi.profileService', 'kifi.keepService', 'kifi.tagService'])

.controller('KeepsCtrl', [
  '$scope', 'profileService', 'keepService', 'tagService',
  function ($scope, profileService, keepService, tagService) {
    $scope.me = profileService.me;

    $scope.$watch(function () {
      return ($scope.keeps && $scope.keeps.length || 0) + ',' + tagService.list.length;
    }, function () {
      //$scope.refreshScroll();
      if ($scope.keeps && $scope.keeps.length && tagService.list.length) {
        keepService.joinTags($scope.keeps, tagService.list);
      }
    });
  }
])

.directive('kfKeeps', [

  function () {

    function delegateFn(scope, name) {
      return function (keep) {
        return scope[name]({
          keep: keep
        });
      };
    }

    return {
      restrict: 'A',
      scope: {
        keeps: '=',
        checkKeep: '&',
        uncheckKeep: '&',
        toggleCheckKeep: '&',
        isCheckedKeep: '&',
        previewKeep: '&',
        togglePreviewKeep: '&',
        isPreviewedKeep: '&',
        scrollDistance: '=',
        scrollDisabled: '=',
        scrollNext: '&'
      },
      controller: 'KeepsCtrl',
      templateUrl: 'keeps/keeps.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        scope.select = delegateFn(scope, 'checkKeep');
        scope.unselect = delegateFn(scope, 'uncheckKeep');
        scope.toggleSelect = delegateFn(scope, 'toggleCheckKeep');
        scope.isSelected = delegateFn(scope, 'isCheckedKeep');
        scope.preview = delegateFn(scope, 'previewKeep');
        scope.togglePreview = delegateFn(scope, 'togglePreviewKeep');
        scope.isPreviewed = delegateFn(scope, 'isPreviewedKeep');

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
