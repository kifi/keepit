'use strict';

angular.module('kifi')

.controller('OrgProfileCreateCtrl', [
  '$scope', '$timeout', 'orgProfileService', '$state', '$stateParams',
  'initParams', 'profileService', 'modalService', 'billingService', '$analytics',
  'originTrackingService', '$location',
  function($scope, $timeout, orgProfileService, $state, $stateParams,
           initParams, profileService, modalService, billingService, $analytics,
           originTrackingService, $location) {
    $scope.orgSlug = ''; // Not yet implemented.
    $scope.disableCreate = false;
    $scope.orgName = '';
    $scope.showSlackPromo = $stateParams.showSlackPromo || initParams.getAndClear('slack');

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
      if (!this.orgName) {
        $scope.$error = { message: 'Please enter a name for your team' };
        return;
      }

      trackPageEvent('click', {
        type: 'createTeam',
        action: 'clickedSubmitTeam'
      });

      $scope.disableCreate = true;
      profileService.savePrefs({ hide_company_name: true });

      orgProfileService
      .createOrg(this.orgName)
      .then(function(org) {
        $scope.$error = null;
        if ($scope.redeemCode) {
          billingService.applyReferralCode(org.id, $scope.redeemCode)['finally'](next);
        } else {
          next();
        }
        function next() {
          profileService.fetchMe();
          profileService.fetchPrefs(); // To invalidate credit code, if any.
          $state.go('orgProfile.libraries', { handle: org.handle, forceSlackDialog: true });
        }
      })
      ['catch'](function () {
        modalService.openGenericErrorModal();
        $scope.disableCreate = false;
      });
    };

    function trackPageView(attributes) {
      var url = $analytics.settings.pageTracking.basePath + $location.url();
      attributes = _.extend(orgProfileService.getCommonTrackingAttributes({ name: $scope.orgName }), attributes);
      attributes = originTrackingService.applyAndClear(attributes);
      $analytics.pageTrack(url, attributes);
    }

    function trackPageEvent(eventType, attributes) {
      if (eventType === 'click') {
        orgProfileService.trackEvent('user_clicked_page', { name: $scope.orgName }, attributes);
      } else if (eventType === 'view') {
        trackPageView(attributes);
      }
    }

    $timeout(function () {
      trackPageEvent('view', { type: 'createTeam' });
    });
  }
]);
