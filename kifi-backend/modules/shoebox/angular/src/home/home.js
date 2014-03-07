'use strict';

angular.module('kifi.home', ['util', 'kifi.keepService', 'kifi.modal'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/', {
      templateUrl: 'home/home.tpl.html',
      controller: 'HomeCtrl'
    });
  }
])

.controller('HomeCtrl', [
  '$scope', 'tagService', 'keepService', '$q', 'injectedState',
  function ($scope, tagService, keepService, $q, injectedState) {
    keepService.reset();


    var messages = {
      0: 'Welcome back!',
      1: 'Thank you for verifying your email address.',
      2: 'Bookmark import in progress. Reload the page to update.'
    };

    function handleInjectedState(state) {
      if (state) {
        if (state.m && state.m === '1') {
          $scope.showEmailModal = true;
          $scope.modal = 'email';
        } else if (state.m) { // show small tooltip
          var msg = messages[state.m];
          console.log(state, state.m, msg)
          $scope.tooltipMessage = msg;
          $timeout(function () {
            delete $scope.tooltipMessage;
          }, 5000);
        }
      }
    }
    handleInjectedState(injectedState.state);

    $scope.keepService = keepService;
    $scope.keeps = keepService.list;

    $scope.toggleSelectAll = keepService.toggleSelectAll;
    $scope.isSelectedAll = keepService.isSelectedAll;

    $scope.isCheckEnabled = function () {
      return $scope.keeps.length;
    };

    $scope.hasMore = function () {
      return !keepService.isEnd();
    };

    $scope.mouseoverCheckAll = false;

    $scope.onMouseoverCheckAll = function () {
      $scope.mouseoverCheckAll = true;
    };

    $scope.onMouseoutCheckAll = function () {
      $scope.mouseoverCheckAll = false;
    };

    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Loading...';
      }

      var subtitle = keepService.getSubtitle($scope.mouseoverCheckAll);
      if (subtitle) {
        return subtitle;
      }

      var numShown = $scope.keeps.length;
      switch (numShown) {
      case 0:
        return 'You have no Keeps';
      case 1:
        return 'Showing your only Keep';
      case 2:
        return 'Showing both of your Keeps';
      default:
        if (keepService.isEnd()) {
          return 'Showing all ' + numShown + ' of your Keeps';
        }
        return 'Showing your ' + numShown + ' latest Keeps';
      }
    };

    $scope.scrollDisabled = false;

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return $q.when([]);
      }

      $scope.loading = true;

      return keepService.getList().then(function (list) {
        $scope.loading = false;

        if (keepService.isEnd()) {
          $scope.scrollDisabled = true;
        }

        return list;
      });
    };

    $scope.getNextKeeps();
  }
]);
