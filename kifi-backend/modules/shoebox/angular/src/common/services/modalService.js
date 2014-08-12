'use strict';

angular.module('kifi.modalService', [])

.factory('modalService', ['$compile', '$rootScope', '$templateCache', '$timeout',
  function ($compile, $rootScope, $templateCache, $timeout) {
    function open (opts) {
      opts = opts || {};

      var $modalParent = angular.element(document.querySelector('.kf-main'));  // $('.kf-main') instead? $(body) instead?
      var scope = $rootScope.$new();
      var template = $templateCache.get('friends/seeMutualFriends.tpl.html'); // need to parameterize this

      // need to parameterize class
      var $modal = angular.element('<div kf-modal class="kf-see-mutual-friends-modal" show="true" kf-width="600px"></div>');
      $modal.html('<div kf-basic-modal-content single-action="false">' + '<div kf-see-mutual-friends></div>' + '</div>');

      if (opts.person) {
        scope.person = opts.person;
      }

      $timeout(function () {
        $compile($modal)(scope);
        $modalParent.append($modal);
      });
    }

    return {
      open: open
    };
  }
]);
