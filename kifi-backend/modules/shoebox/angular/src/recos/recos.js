'use strict';

angular.module('kifi')

.controller('RecosCtrl', [
  '$scope',
  '$rootScope',
  '$analytics',
  '$timeout',
  '$window',
  'keepActionService',
  'recoActionService',
  'recoDecoratorService',
  'tagService',
  'undoService',
  function ($scope, $rootScope, $analytics, $timeout, $window, keepActionService,
    recoActionService, recoDecoratorService, tagService, undoService) {
    $window.document.title = 'Kifi • Your Recommendation List';

    $scope.recos = [];
    $scope.loading = true;
    $scope.recosState = 'hasRecos';
    $scope.initialCardClosed = false;

    $scope.getMore = function (opt_recency) {
      $scope.recos = [];
      $scope.loading = true;
     
      recoActionService.getMore(opt_recency).then(function (rawRecos) {
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
      
      var trashedRecoIndex = _.findIndex($scope.recos, reco);
      var trashedReco = $scope.recos.splice(trashedRecoIndex, 1)[0];

      // If the user has trashed all the recommendations, reload a new set of
      // recommendations.
      if ($scope.recos.length === 0) {
        $scope.getMore();
      }

      undoService.add('Recommendation removed.', function () {
        $scope.recos.splice(trashedRecoIndex, 0, trashedReco);
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

    $scope.trackRecoKeep = function (recoKeep) {
      recoActionService.trackKeep(recoKeep);
    };

    $scope.trackRecoClick = function (recoKeep) {
      recoActionService.trackClick(recoKeep);
    };
  }
])

.directive('kfRecoDropdownMenu', ['$document', 'recoActionService',
  function ($document, recoActionService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        reco: '=',
        showImprovementModal: '&'
      },
      templateUrl: 'recos/recoDropdownMenu.tpl.html',
      link: function (scope, element/*, attrs*/) {
        var dropdownArrow = element.find('.kf-reco-dropdown-menu-down');
        var dropdownMenu = element.find('.kf-dropdown-menu');
        var show = false;

        // Clicking outside the dropdown menu will close the menu.
        function onMouseDown(event) {
          if ((dropdownMenu.find(event.target).length === 0) &&
              (!event.target.classList.contains('kf-reco-dropdown-menu-down'))) {
            hideMenu();
          }
        }

        function showMenu() {
          show = true;
          dropdownMenu.show();
          $document.on('mousedown', onMouseDown);
        }

        function hideMenu() {
          show = false;
          dropdownMenu.hide();
          $document.off('mousedown', onMouseDown);
        }

        dropdownArrow.on('click', function () {
          if (show) {
            hideMenu();
          } else {
            showMenu();
          }
        });

        scope.upVote = function (reco) {
          hideMenu();
          recoActionService.vote(reco.recoKeep, true);
        };

        scope.downVote = function (reco) {
          hideMenu();
          recoActionService.vote(reco.recoKeep, false);
        };

        scope.showModal = function () {
          hideMenu();
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


