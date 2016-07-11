'use strict';

angular.module('kifi')

.directive('kfAnnouncementBanner', [
  function() {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        hide: '='
      },
      templateUrl: 'home/announcementBanner.tpl.html'
    };
  }
]);
