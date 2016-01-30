'use strict';

angular.module('kifi')

.controller('ActivityLogCtrl', [
  '$scope', '$timeout', 'billingService', 'profile', 'billingState', 'modalService', 'Paginator',
  function ($scope, $timeout, billingService, profile, billingState, modalService, Paginator) {
    $scope.billingState = billingState;
    $scope.trackingType = 'org_profile:settings:activity_log';

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

    $scope.trackActivityClick = function(url) {
      $scope.$emit('trackOrgProfileEvent', 'click',
        {
          type: $scope.trackingType,
          action: url.toLowerCase().indexOf('plan') !== -1 ? 'activity_log:plan' : 'link'
        }
      );
    };

    $timeout(function () {
      $scope.$emit('trackOrgProfileEvent', 'view', {
        type: 'org_profile:settings:activity_log'
      });
    });
  }
]);
