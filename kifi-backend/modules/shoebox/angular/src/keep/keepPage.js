'use strict';

angular.module('kifi')

.controller('KeepPageCtrl', [ '$rootScope', '$location', '$scope', '$state',
    '$stateParams', '$timeout', '$window', '$analytics', 'keepActionService', 'modalService', 'util',
  function ($rootScope, $location, $scope, $state, $stateParams, $timeout, $window, $analytics, keepActionService, modalService, util) {

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
      var keepSource = !$scope.keep.sourceAttribution ? null : ($scope.keep.sourceAttribution.twitter ? 'twitterSync' : 'slack');
      var props = {
        type: 'keepPage',
        keepSource: keepSource,
        keepId: $scope.keep.id
      };
      $analytics.eventTrack($rootScope.userLoggedIn ? 'user_viewed_page' : 'visitor_viewed_page', props);
    }

    $scope.enableRHR = profileService.hasExperiment('keep_page_rhr');
    $scope.maxInitialComments = 15;
    keepActionService.getFullKeepInfo($stateParams.pubId, $stateParams.authToken, $scope.maxInitialComments * 2).then(function (result) {
      $scope.loaded = true;
      $scope.keep = result;
      var displayTitle = result.title || result.summary && result.summary.title || util.formatTitleFromUrl(result.url);
      if (displayTitle) {
        $window.document.title = 'Kifi • ' + displayTitle;
      } else {
        $window.document.title = 'Kifi';
      }

      $timeout(trackPageView);
    })['catch'](function(reason){
      $scope.loaded = true;
      $window.document.title = 'Kifi';
      $rootScope.$emit('errorImmediately', reason);
    });
  }
]);
