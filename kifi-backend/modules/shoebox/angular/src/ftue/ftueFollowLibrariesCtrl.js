'use strict';

angular.module('kifi')

  .controller('FtueFollowLibrariesCtrl', [
    '$state', '$rootScope', '$scope', '$stateParams', '$analytics', '$location',
    'profileService', 'libraryService', 'originTrackingService',
    function ($state, $rootScope, $scope, $stateParams, $analytics, $location,
              profileService, libraryService, originTrackingService) {

      // it's nicer to use just VIEW_STATES here
      // but it's also nice to be able to reference
      // VIEW_STATES directly in the html
      var VIEW_STATES = {
        LOADING: 'LOADING',
        LOADED: 'LOADED',
        FOLLOWING: 'FOLLOWING'
      };
      $scope.VIEW_STATES = VIEW_STATES;

      $scope.viewState = VIEW_STATES.LOADING;
      $scope.libraries = [];
      $scope.selectedLibraries = [];
      $scope.fetchLibraries = function () {

        libraryService
          .getFtueLibraries()
          .then(function (data) {
            $scope.viewState = VIEW_STATES.LOADED;
            return data.data;
          }).then(function (libs) {
            $scope.libraries = libs;
            $scope.libraries.forEach(function (lib) {
              lib.checked = true;
            });
            $scope.selectAll = true;
          });
      };

      $scope.onClickedSkip = function() {
        profileService.savePrefs({has_seen_ftue: true});
        $state.go('home.feed');
      };

      $scope.test = function() {

      }

      $scope.onClickedFollowBtn = function () {

        trackPageClick({
          action: 'skip'
        });
        var selected = $scope.libraries.filter(function (lib) {
          return lib.checked;
        });
        var ids = selected.map(function (lib) {
          return lib.id;
        });

        trackPageClick({
          action: 'follow',
          num_libraries_followed: ids.length + ''
        });

        $scope.viewState = VIEW_STATES.FOLLOWING;
        libraryService.joinLibraries(ids)
          .then(function () {
            profileService.savePrefs({has_seen_ftue: true});
            $state.go('home.feed');
          }, function () {
            $scope.viewState = VIEW_STATES.LOADED;
          });

      };

      $scope.selectedLibraryCount = function () {
        var i = 0;
        if ($scope.libraries) {
          $scope.libraries.forEach(function (lib) {
            i += !!lib.checked;
          });
        }
        return i;
      };

      $scope.fetchLibraries();


      $scope.onSelectAllChanged = function (checked) {
        trackPageClick({
          action: checked ? 'select_all' : 'select_none'
        });
        $scope.libraries.forEach(function (lib) {
          lib.checked = checked;
        });
      };

      $scope.onLibrarySelectionChanged = function (checked) {
        if (!checked) {
          $scope.selectAll = false;
        }
      };

      $scope.selectAll = false;

      function trackPageView() {
        var url = $analytics.settings.pageTracking.basePath + $location.url();
        $analytics.pageTrack(url, originTrackingService.applyAndClear({
          type: 'getStarted',
          action: 'testing'
        }));
      }

      // test
      trackPageView();

      function trackPageClick(attributes) {
        var attrs = _.extend(attributes || {}, {
          type: 'getStarted'
        });

        $analytics.eventTrack($rootScope.userLoggedIn ? 'user_clicked_page' : 'visitor_clicked_page', attrs);
      }

    }

  ]);
