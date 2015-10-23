'use strict';

angular.module('kifi')

.controller('ActivityLogCtrl', [
  '$scope', '$analytics', '$timeout', 'billingService', 'profile', 'billingState', 'modalService', 'Paginator',
  function ($scope, $analytics, $timeout, billingService, profile, billingState, modalService, Paginator) {
    $scope.billingState = billingState;

    var activityLogPaginator = new Paginator(activitySource);

    function getEvents(billingEventData) {
      return billingEventData.events;
    }

    function activitySource(pageNumber, pageSize) {
      var bottomEvent = $scope.billingEvents && $scope.billingEvents.slice(-1).pop();

      return billingService
      .getBillingEvents(profile.organization.id, pageSize, bottomEvent && bottomEvent.id)
      .then(getEvents);
    }

    $scope.billingEvents = null;

    $scope.fetch = function () {
      activityLogPaginator
      .fetch()
      .then(function (billingEvents) {
        $scope.billingEvents = billingEvents;
      })
      ['catch'](modalService.openGenericErrorModal);
    };

    $scope.hasMore = function () {
      return activityLogPaginator.hasMore();
    };

    $scope.isLoading = function () {
      return !activityLogPaginator.hasLoaded();
    };

    $scope.fetch();

    $timeout(function () {
      $analytics.eventTrack('user_viewed_page', {
        type: 'activityLog'
      });
    });
  }
]);
