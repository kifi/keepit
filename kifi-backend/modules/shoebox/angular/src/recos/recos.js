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
  '$scope', '$rootScope', '$analytics', '$timeout', '$window', 'keepActionService', 'keepService', 'recoActionService', 'recoDecoratorService', 'tagService',
  function ($scope, $rootScope, $analytics, $timeout, $window, keepActionService, keepService, recoActionService, recoDecoratorService, tagService) {
    $window.document.title = 'Kifi • Your Recommendation List';

    $scope.recos = [];
    $scope.loading = true;
    $scope.recosState = 'hasRecos';
    $scope.initialCardClosed = false;

    $scope.getMore = function (recency) {
      $scope.recos = [];
      $scope.loading = true;
     
      recoActionService.getMore(recency).then(function (rawRecos) {
        if (rawRecos.length > 0) {
          rawRecos.forEach(function (rawReco) {
            $scope.recos.push(recoDecoratorService.newUserRecommendation(rawReco));
          });

          $scope.recosState = 'hasRecos';
        } else {
          $scope.recosState = 'noMoreRecos';
        }

        $scope.loading = false;
      });
    };

    $scope.trash = function (reco) {
      recoActionService.trash(reco.recoKeep);
      _.pull($scope.recos, reco);
    };

    $scope.keepReco = function (reco) {
      recoActionService.keep(reco.recoKeep);

      keepActionService.keepPublic(reco.recoKeep).then(function (keptKeep) {
        reco.recoKeep.id = keptKeep.id;
        reco.recoKeep.isPrivate = keptKeep.isPrivate;

        keepService.buildKeep(reco.recoKeep);
        tagService.addToKeepCount(1);
      });
    };

    $scope.showPopular = function () {
      $scope.loading = true;

      recoActionService.getPopular().then(function (rawRecos) {
        rawRecos.forEach(function (rawReco) {
          $scope.recos.push(recoDecoratorService.newPopularRecommendation(rawReco));
        });

        $scope.loading = false;
        $scope.recosState = 'hasPopularRecos';
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

    recoActionService.get().then(function (rawRecos) {
      if (rawRecos.length > 0) {
        rawRecos.forEach(function (rawReco) {
          $scope.recos.push(recoDecoratorService.newUserRecommendation(rawReco));
        });

        $scope.loading = false;
        $scope.recosState = 'hasRecos';
      } else {
        $scope.recosState = 'noRecos';

        // If the user has no recommendations, show some popular
        // keeps/libraries as recommendations.
        recoActionService.getPopular().then(function (rawRecos) {
          rawRecos.forEach(function (rawReco) {
            $scope.recos.push(recoDecoratorService.newPopularRecommendation(rawReco));
          });

          $scope.loading = false;
        });
      }

    });
  }
])

// For individual recommendation
.controller('RecoCtrl', [
  '$scope', 'recoActionService',
  function ($scope, recoActionService) {
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
      recoActionService.improve(reco.recoKeep, $scope.improvement.type);
    };
  }
])

.directive('kfRecoDropdownMenu', ['recoActionService',
  function (recoActionService) {
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
          recoActionService.vote(reco.recoKeep, true);
        };

        scope.downVote = function (reco) {
          dropdownMenu.hide();
          recoActionService.vote(reco.recoKeep, false);
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


