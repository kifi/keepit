'use strict';

angular.module('kifi')

.controller('ActivityLogCtrl', [
  '$scope', 'billingService', 'profile',
  function ($scope, billingService, profile) {
    billingService
    .getBillingEvents(profile.organization.id, 20)
    .then(function (billingEventData) {
      $scope.billingEvents = billingEventData.events;
    });
  }
]);
