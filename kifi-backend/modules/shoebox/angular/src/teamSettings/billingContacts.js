'use strict';

angular.module('kifi')

.controller('BillingContactsCtrl', [
  '$window', '$scope', '$q', 'orgProfileService', 'billingService',
  'modalService', 'messageTicker',
  function ($window, $scope, $q, orgProfileService, billingService,
            modalService, messageTicker) {

    $scope.billingContactModel = {};

    var getOrgAdmins = orgProfileService
    .getOrgMembers($scope.profile.id, 1, 30)
    .then(function (membersData) {
      return membersData.members.filter(function (m) {
        return m.role === 'admin';
      });
    });

    $q.all([
      getOrgAdmins,
      billingService.getBillingContacts($scope.profile.id)
    ]).then(function (results) {
      var admins = results[0];
      var contacts = results[1];

      admins.forEach(function (a) {
        var contact = contacts.filter(function (c) { return c.who.id === a.id; })[0];
        $scope.billingContactModel[a.id] = { id: a.id, enabled: contact ? contact.enabled : false };
      });

      $scope.admins = admins;
    });


    $scope.setBillingContacts = function () {
      var contactList = Object.keys($scope.billingContactModel).map(function (key) {
        return $scope.billingContactModel[key];
      });

      billingService
      .setBillingContacts($scope.profile.id, contactList)
      .then(function () {
        messageTicker({
          text: 'Settings have been saved',
          type: 'green'
        });
      })
      ['catch'](function (response) {
        messageTicker({
          text: response.statusText + ': There was an error saving your settings',
          type: 'red'
        });
      });
    };
  }
]);
