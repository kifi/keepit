'use strict';

angular.module('kifi')

.controller('ActivityLogCtrl', [
  '$scope', 'billingService', 'profile', 'modalService', 'Paginator',
  function ($scope, billingService, profile, modalService, Paginator) {

    var activityLogPaginator = new Paginator(activitySource);

    function getEvents(billingEventData) {
      return billingEventData.events;
    }

    function activitySource(pageNumber, pageSize) {
      var bottomEvent = $scope.billingEvents && $scope.billingEvents.slice(-1).pop();

      if (bottomEvent) {
        // we already have some events, so use getBillingEventsBefore
        return billingService
        .getBillingEventsBefore(profile.organization.id, pageSize, bottomEvent.eventTime, bottomEvent.id)
        .then(getEvents);
      } else {
        return billingService
        .getBillingEvents(profile.organization.id, pageSize)
        .then(getEvents);
      }
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
  }
]);
