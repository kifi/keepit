'use strict';

angular.module('kifi')

.controller('RecosCtrl', [
  '$scope',
  '$rootScope',
  '$analytics',
  '$window',
  'modalService',
  'recoActionService',
  'recoDecoratorService',
  'recoStateService',
  'undoService',
  'libraryService',
  function ($scope, $rootScope, $analytics, $window,
    modalService, recoActionService, recoDecoratorService, recoStateService, undoService, libraryService) {
    $window.document.title = 'Kifi • Your Recommendation List';

    $scope.recos = recoStateService.recosList;
    $scope.recosState = 'loading';
    $scope.initialCardClosed = false;

    $scope.librariesEnabled = libraryService.isAllowed;

    $scope.getMore = function (opt_recency) {
      $scope.loading = true;

      recoStateService.empty();
      recoActionService.getMore(opt_recency).then(function (rawRecos) {
        if (rawRecos.length > 0) {
          var recos = [];
          rawRecos.forEach(function (rawReco) {
            recos.push(recoDecoratorService.newUserRecommendation(rawReco));
          });
          recoStateService.populate(recos);

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

      recoStateService.empty();
      recoActionService.getPopular().then(function (rawRecos) {
        var recos = [];
        rawRecos.forEach(function (rawReco) {
          recos.push(recoDecoratorService.newPopularRecommendation(rawReco));
        });
        recoStateService.populate(recos);

        $scope.recosState = 'hasPopularRecos';
        $scope.loading = false;
      });
    };

    $scope.importBookmarks = function () {
      var kifiVersion = $window.document.documentElement.getAttribute('data-kifi-ext');

      if (!kifiVersion) {
        modalService.open({
          template: 'common/modal/installExtensionModal.tpl.html',
          scope: $scope
        });
      }

      $rootScope.$emit('showGlobalModal', 'importBookmarks');
      $analytics.eventTrack('user_viewed_page', {
        'type': 'browserImport'
      });
    };

    $scope.closeInitialCard = function () {
      $scope.initialCardClosed = true;
    };


    /*
    This is intended be called from the console only, for debugging.
    Specifically, running `$(".recos-view").scope().toggleExplain(); $(".recos-view").scope().$digest();`
    in the bowser console while on the recommendations page will toggle between showing the page description and the score breakdown in the card.
    */
    $scope.toggleExplain = function () {
      $scope.recos.forEach(function (reco) {
        var temp = reco.recoKeep.summary.description;
        reco.recoKeep.summary.description = reco.recoData.explain;
        reco.recoData.explain = temp;
      });
    };

    $rootScope.$emit('libraryUrl', {});

    // Load a new set of recommendations only on page refresh.
    // Otherwise, load the recommendations we have previously shown.
    if (recoStateService.recosList.length === 0) {
      $scope.loading = true;

      recoStateService.empty();
      recoActionService.get().then(function (rawRecos) {
        var recos = [];
        //really hacky way of preventing user from seeing their personalized recos if they haven't followed a library
        //if this is still here after Oct 10th 2014 throw something (soft) at Stephen
        libraryService.fetchLibrarySummaries().then(function (librarySummaries){
          if (rawRecos.length > 0 && librarySummaries.libraries.length>2) {
            rawRecos.forEach(function (rawReco) {
              recos.push(recoDecoratorService.newUserRecommendation(rawReco));
            });
            recoStateService.populate(recos);
            $scope.recosState = 'hasRecos';
            $scope.loading = false;
          } else {
            // If the user has no recommendations, show some popular
            // keeps/libraries as recommendations.
            recoActionService.getPopular().then(function (rawRecos) {
              rawRecos.forEach(function (rawReco) {
                recos.push(recoDecoratorService.newPopularRecommendation(rawReco));
              });
              recoStateService.populate(recos);
              $scope.recosState = 'noRecos';
              $scope.loading = false;
            });
          }
        });
      });
    }
  }
])

// For individual recommendation
.controller('RecoCtrl', [
  '$scope', 'modalService', 'recoActionService',
  function ($scope, modalService, recoActionService) {
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

    $scope.improvement = {};

    $scope.showImprovementModal = function () {
      modalService.open({
        template: 'recos/recoImproveModal.tpl.html',
        scope: $scope
      });
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


