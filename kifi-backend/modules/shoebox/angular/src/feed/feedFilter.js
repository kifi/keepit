'use strict';

angular.module('kifi')

.directive('kfFeedFilter', [ '$timeout',
  function($timeout) {
    return {
      restrict: 'A',
      templateUrl: 'feed/feedFilter.tpl.html',
      replace: false,
      scope: {
        filter: '=',
        updateCallback: '&'
      },
      link: function(scope, element) {
        scope.fitWidthToSelectedOption = function() {
          var selectedOption = element.find('.kf-feed-filter-display option:selected')[0];
          var hiddenOption = element.find('.kf-feed-filter-hidden option:selected')[0];
          hiddenOption.innerHTML = selectedOption.innerHTML;
          var displaySelect = element.find('.kf-feed-filter-display');
          var hiddenSelect = element.find('.kf-feed-filter-hidden');
          displaySelect.width(hiddenSelect.width() + 3);
        };

        scope.updateSelect = function() {
          scope.updateCallback();
          scope.fitWidthToSelectedOption();
        };

        $timeout(function () {
          scope.fitWidthToSelectedOption();
        }, 1);
      }
    };
  }
]);
