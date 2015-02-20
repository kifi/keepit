'use strict';

angular.module('kifi')

.directive('kfSuggestedSearches', [
  '$location', 'platformService',
  function ($location, platformService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        parentLibrary: '&'
      },
      templateUrl: 'libraries/suggestedSearches.tpl.html',
      link: function (scope/*, element, attrs*/) {
        if (!platformService.isSupportedMobilePlatform()) {
          var parentLibrary = scope.parentLibrary();
          scope.termsWithActions = _.map(parentLibrary.suggestedSearches, function (term) {
            return {
              name: term,
              action: function () {
                $location.url(parentLibrary.url + '/find?q=' + term);
              }
            };
          });
        }
      }
    };
  }
]);
