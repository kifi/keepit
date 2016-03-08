'use strict';

angular.module('kifi')

.controller('KeepPageCtrl', [ '$rootScope', '$location', '$scope', '$state',
    '$stateParams', '$timeout', '$analytics', 'keepActionService', 'modalService',
  function ($rootScope, $location, $scope, $state, $stateParams, $timeout, $analytics, keepActionService, modalService) {

    $scope.unkeepFromLibrary = function (event, keep) {
      if (keep.libraryId && keep.id) {
        keepActionService.unkeepFromLibrary(keep.libraryId, keep.id).then(function () {
          var libPath = keep.library && keep.library.path;
          if (libPath) {
            $location.path(keep.library.path);
          } else {
            $state.go('home.feed');
          }
        })['catch'](function (err) {
          modalService.openGenericErrorModal(err);
        });
      }
    };

    function trackPageView() {
      var keepSource = $scope.keep.sourceAttribution.twitter ? 'twitterSync' : ($scope.keep.sourceAttribution.slack ? 'slack' : null);
      var props = {
        type: 'keepPage',
        keepSource: keepSource,
        keepId: $scope.keep.id
      };
      $analytics.eventTrack($rootScope.userLoggedIn ? 'user_viewed_page' : 'visitor_viewed_page', props);
    }

    keepActionService.getFullKeepInfo($stateParams.pubId, $stateParams.authToken).then(function (result) {
      $scope.loaded = true;
      $scope.keep = result;
      $timeout(trackPageView);
    })['catch'](function(reason){
      $scope.loaded = true;
      $rootScope.$emit('errorImmediately', reason);
    });
  }
]);
