'use strict';

angular.module('kifi')

.controller('OrgProfileCreateCtrl', [
  '$scope', '$timeout', '$q', 'orgProfileService', '$state', '$stateParams',
  'initParams', 'profileService', 'modalService', 'billingService', '$analytics',
  'originTrackingService', '$location',
  function($scope, $timeout, $q, orgProfileService, $state, $stateParams,
           initParams, profileService, modalService, billingService, $analytics,
           originTrackingService, $location) {
    $scope.orgSlug = ''; // Not yet implemented.
    $scope.disableCreate = false;
    $scope.orgName = '';
    $scope.showSlackPromo = $stateParams.showSlackPromo || initParams.getAndClear('slack');

    var me = profileService.me;
    (Object.keys(profileService.prefs) === 0 ? profileService.fetchPrefs() : $q.when(profileService.prefs)).then(function(prefs){
      $scope.redeemCode = prefs.stored_credit_code;
      if (prefs.company_name && !orgNameExists(prefs.company_name)) {
        $scope.orgName = prefs.company_name;
      } else {
        var potentialEmails = potentialCompanyEmails(me.emails);
        $scope.orgName = (potentialEmails[0] && getEmailDomain(potentialEmails[0].address)) || '';
      }
    });

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

      if (profileService.shouldBeWindingDown()) {
        modalService.showWindingDownModal();
        return;
      }

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
          profileService.fetchPrefs(true); // To invalidate credit code, if any.
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
