'use strict';

angular.module('kifi')

.controller('OrgProfileCreateCtrl', [
  '$scope', '$timeout', 'orgProfileService', '$state', 'profileService', 'modalService', 'billingService',
  function($scope, $timeout, orgProfileService, $state, profileService, modalService, billingService) {
    $scope.orgSlug = ''; // Not yet implemented.
    $scope.disableCreate = false;
    $scope.orgName = '';

    var me = profileService.me;
    $scope.$watch(function () {
      return profileService.prefs.stored_credit_code;
    }, function () {
      if (profileService.prefs.stored_credit_code) {
        $scope.redeemCode = profileService.prefs.stored_credit_code;
      }
    });

    if (!profileService.prefs.company_name) {
      profileService.fetchPrefs().then(function (prefs) {
        if (prefs.company_name && !orgNameExists(prefs.company_name)) {
          $scope.orgName = prefs.company_name;
        } else {
          var potentialEmails = potentialCompanyEmails(me.emails);
          $scope.orgName = potentialEmails[0] && getEmailDomain(potentialEmails[0].address);
        }
      });
    } else {
      $scope.orgName = (!orgNameExists(profileService.prefs.company_name) && profileService.prefs.company_name) || '';
    }

    function potentialCompanyEmails(emails) {
      return emails.filter(function(email){
        return !email.isOwned && !email.isFreeMail;
      });
    }

    var domainSuffixRegex = /\..*$/;

    function getEmailDomain(address) {
      var domain = address.slice(address.lastIndexOf('@') + 1).replace(domainSuffixRegex, '');
      return domain[0].toUpperCase() + domain.slice(1, domain.length);
    }

    function orgNameExists(companyName) {
      var orgNames = profileService.me.orgs.map(
        function(org) {
          return org.name.toLowerCase();
        }
      );
      return orgNames.indexOf(companyName.toLowerCase()) !== -1;
    }

    $scope.createOrg = function() {
      $scope.disableCreate = true;

      orgProfileService
      .createOrg(this.orgName)
      .then(function(org) {
        if ($scope.redeemCode) {
          billingService.applyReferralCode(org.id, $scope.redeemCode)['finally'](next);
        } else {
          next();
        }
        function next() {
          profileService.fetchMe();
          profileService.fetchPrefs(); // To invalidate credit code, if any.
          $state.go('orgProfile.libraries', { handle: org.handle, openInviteModal: true, addMany: true  });
        }
      })
      ['catch'](function () {
        modalService.openGenericErrorModal();
        $scope.disableCreate = false;
      });
    };

    $timeout(function () {
      $scope.$emit('trackOrgProfileEvent', 'view', {
        type: 'createTeam'
      });
    });
  }
]);
