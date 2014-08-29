'use strict';

angular.module('kifi')

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider.when('/recommendation', {
      templateUrl: 'recos/recosView.tpl.html'
    });
  }
])

.controller('RecosCtrl', [
  '$scope', '$rootScope', '$analytics', '$timeout', '$window', 'recoService', 'tagService',
  function ($scope, $rootScope, $analytics, $timeout, $window, recoService, tagService) {
    $window.document.title = 'Kifi • Your Recommendation List';

    $scope.recosState = 'hasRecos';
    $scope.initialCardClosed = false;

    $scope.getMore = function (recency) {
      $scope.loading = true;
      $scope.recos = [];
     
      recoService.getMore(recency).then(function (recos) {
        $scope.loading = false;

        if (recos.length > 0) {
          $scope.recosState = 'hasRecos';
          $scope.recos = recos;
        } else {
          $scope.recosState = 'noMoreRecos';
          $scope.recos = [];
        }
      });
    };

    $scope.trash = function (reco) {
      recoService.trash(reco.recoKeep);
      _.pull($scope.recos, reco);
    };

    $scope.loading = true;
    recoService.get().then(function (recos) {
      if (recos.length > 0) {
        $scope.loading = false;
        $scope.recosState = 'hasRecos';
        $scope.recos = recos;
      } else {
        $scope.recosState = 'noRecos';

        // If the user has no recommendations, show some popular
        // keeps/libraries as recommendations.
        recoService.getPopular().then(function (recos) {
          $scope.loading = false;
          $scope.recos = recos;
        });
      }
    });

    $scope.showPopular = function () {
      $scope.loading = true;

      recoService.getPopular().then(function (recos) {
        $scope.loading = false;
        $scope.recosState = 'hasPopularRecos';
        $scope.recos = recos;
      });
    };

    $scope.importBookmarks = function () {
      var kifiVersion = $window.document.documentElement.getAttribute('data-kifi-ext');

      if (!kifiVersion) {
        $rootScope.$emit('showGlobalModal','installExtension');
        return;
      }

      $rootScope.$emit('showGlobalModal', 'importBookmarks');
      $analytics.eventTrack('user_viewed_page', {
        'type': 'browserImport'
      });
    };

    $scope.closeInitialCard = function () {
      $scope.initialCardClosed = true;
    };

    $scope.$watch(tagService.getTotalKeepCount, function (val) {
      $scope.numKeptItems = val;
    });
  }
])

// For individual recommendation
.controller('RecoCtrl', [
  '$scope', 'recoService',
  function ($scope, recoService) {
    $scope.reasons = $scope.reco.recoData.reasons;
    $scope.reasonIndex = 0;

    $scope.hasReason = function () {
      return $scope.reco.recoData.reasons &&
        ($scope.reco.recoData.reasons.length > 0) &&
        $scope.reco.recoData.reasons[$scope.reasonIndex].kind;
    };

    $scope.hasTwoOrMoreReasons = function () {
      return $scope.hasReason() && ($scope.reco.recoData.reasons.length > 1);
    };

    $scope.gotoNextReason = function () {
      $scope.reasonIndex = ($scope.reasonIndex + 1) % $scope.reco.recoData.reasons.length;
    };

    $scope.gotoPrevReason = function () {
      $scope.reasonIndex = ($scope.reco.recoData.reasons.length + $scope.reasonIndex - 1) % $scope.reco.recoData.reasons.length;
    };

    $scope.showRecoImproveModal = false;
    $scope.improvement = {};

    $scope.showImprovementModal = function () {
      $scope.showRecoImproveModal = true;
    };

    $scope.submitImprovement = function (reco) {
      recoService.improve(reco.recoKeep, $scope.improvement.type);
    };
  }
])

.directive('kfRecoDropdownMenu', ['recoService',
  function (recoService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        reco: '=',
        showImprovementModal: '&'
      },
      templateUrl: 'recos/recoDropdownMenu.tpl.html',
      link: function (scope, element/*, attrs*/) {
        var dropdownArrow= element.find('.kf-reco-dropdown-menu-down');
        var dropdownMenu = element.find('.kf-dropdown-menu');

        dropdownArrow.on('click', function () {
          dropdownMenu.toggle();
        });

        scope.upVote = function (reco) {
          dropdownMenu.hide();
          recoService.vote(reco.recoKeep, true);
        };

        scope.downVote = function (reco) {
          dropdownMenu.hide();
          recoService.vote(reco.recoKeep, false);
        };

        scope.showModal = function () {
          dropdownMenu.hide();
          scope.showImprovementModal();
        };
      }
    };
  }
])

.directive('kfRecoRecencySlider', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        getMore: '&'
      },
      templateUrl: 'recos/recoRecencySlider.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.recency = { value: 0.75 };
      }
    };
  }
]);


