'use strict';

angular.module('kifi')

.controller('OrgProfileSettingsCtrl', [
  '$window', '$scope', '$timeout', 'settings',
  function ($window, $scope, $timeout, settings) {
    $scope.settings = settings.settings;

    function onHashChange() {
      var anchor = angular.element($window.location.hash.slice(0, -1))[0];

      if (anchor) {
        angular.element('html, body').animate({
          scrollTop: anchor.getBoundingClientRect().top - $window.document.body.getBoundingClientRect().top
        });
      }
    }

    $window.addEventListener('hashchange', onHashChange, false);
    $scope.$on('$destroy', function () {
      $window.removeEventListener('hashchange', onHashChange);
    });
  }
]);
