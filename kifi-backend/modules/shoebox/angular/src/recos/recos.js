'use strict';

angular.module('kifi')

.controller('RecosCtrl', [
  '$scope', '$rootScope', '$analytics', '$window', 'profileService',
  'modalService', 'recoActionService', 'recoDecoratorService', 'recoStateService', 'undoService',
  function ($scope, $rootScope, $analytics, $window, profileService,
    modalService, recoActionService, recoDecoratorService, recoStateService, undoService) {
    $window.document.title = 'Kifi • Your Recommendation List';

    $scope.recos = recoStateService.recosList;
    $scope.recosState = 'loading';
    $scope.initialCardClosed = false;
    $scope.noMoreRecos = false;
    $scope.showHints = false;
    $scope.autoShowPersona = undefined;

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

    $scope.addMore = function () {
      $scope.moreLoading = true;
      recoActionService.getMore().then(function (rawRecos) {
        if (rawRecos.length > 0) {
          var recos = [];
          rawRecos.forEach(function (rawReco) {
            recos.push(recoDecoratorService.newUserRecommendation(rawReco));
          });
          if (!recoStateService.populate(recos)) {
            $scope.noMoreRecos = true;
          }
        } else {
          $scope.noMoreRecos = true;
        }
      })['catch'](function () {
        $scope.noMoreRecos = true;
      })['finally'](function () {
        $scope.moreLoading = false;
      });
    };

    $scope.trash = function (reco) {
      if (reco.recoData) {
        recoActionService.trash(reco);
      }

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

    $scope.closeInitialCard = function () {
      $scope.initialCardClosed = true;
    };

    // click on 'update your interests'
    $scope.showPersonaModal = function() {
      $analytics.eventTrack('user_clicked_page', {type: 'yourKeeps', action: 'clickedUpdateInterests'});
      modalService.open({
        template: 'persona/managePersonaModal.tpl.html'
      });
    };

    $scope.closeAutoShowPersonas = function() {
      profileService.savePrefs({'auto_show_persona' : null});
    };

    function reloadRecos(invalidate, setRecosDelivered) {
      $scope.loading = true;

      recoStateService.empty();
      recoActionService.get(invalidate, setRecosDelivered).then(function (rawRecos) {
        var recos = [];
        if (rawRecos.length > 0) {
          rawRecos.forEach(function (rawReco) {
            recos.push(recoDecoratorService.newUserRecommendation(rawReco));
          });
          recoStateService.populate(recos);
          $scope.recosState = 'hasRecos';
          $scope.loading = false;
          $scope.noMoreRecos = false;
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
            $scope.noMoreRecos = false;
          });
        }
      });
    }

    function removeAlreadyKeptKeeps() {
      _.remove($scope.recos, function (reco) {
        return reco && reco.recoKeep && reco.recoKeep.isMyBookmark;
      });
    }

    function initRecos() {
      // Load a new set of recommendations only on page refresh.
      // Otherwise, load the recommendations we have previously shown.
      removeAlreadyKeptKeeps();
      if ($scope.recos.length > 0) {
        $scope.recosState = 'hasRecos';
      } else {
        reloadRecos(false, $scope.setRecosDelivered);
        $scope.setRecosDelivered = undefined;
      }
    }

    $scope.showDelightedSurvey = profileService.prefs.show_delighted_question;
    $scope.$on('$destroy', $rootScope.$on('prefsChanged', function () {
      $scope.showDelightedSurvey = profileService.prefs.show_delighted_question;
    }));

    $scope.hideDelightedSurvey = function () {
      $scope.showDelightedSurvey = false;
    };

    [
      $rootScope.$on('refreshRecos', function () {
        reloadRecos(true);
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });

    $scope.setRecosDelivered = undefined;
    var unregisterAutoShowPersona = $scope.$watch(function () {
      return profileService.prefs.auto_show_persona;
    }, function (newValue, oldValue) {
      $scope.autoShowPersona = newValue;

      // stop listening when autoShowPersona from true -> null (means we're closing the auto-show-persona)
      if (!newValue && oldValue) {
        $scope.showHints = true;
        $scope.setRecosDelivered = false;
        unregisterAutoShowPersona();
      }

      // load recommendations only when autoShowPersona is set to non-true
      if (newValue === null) {
        initRecos();
      }
    });
  }
])

// For individual recommendation
.controller('RecoCtrl', [
  '$scope', 'modalService', 'recoActionService',
  function ($scope, modalService, recoActionService) {
    $scope.improvement = {};

    $scope.showImprovementModal = function () {
      modalService.open({
        template: 'recos/recoImproveModal.tpl.html',
        scope: $scope
      });
    };

    $scope.submitImprovement = function (reco) {
      if (reco.recoData && reco.recoData.kind === 'keep') {
        recoActionService.improve(reco.recoKeep, $scope.improvement.type);
      }
    };

    $scope.trackRecoKeep = function (recoKeep) {
      recoActionService.trackKeep(recoKeep);
    };

    $scope.trackLibraryFollow = function (recoLib) {
      recoActionService.trackFollow(recoLib);
    };

    $scope.trackRecoClick = function (reco) {
      recoActionService.trackClick(reco);
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
      link: function (scope, element) {
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

          if (reco.recoData) {
            recoActionService.vote(reco, true);
          }
        };

        scope.downVote = function (reco) {
          hideMenu();

          if (reco.recoData) {
            recoActionService.vote(reco, false);
          }
        };

        scope.showModal = function () {
          hideMenu();
          scope.showImprovementModal();
        };
      }
    };
  }
]);
