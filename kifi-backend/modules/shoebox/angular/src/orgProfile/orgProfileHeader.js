'use strict';

angular.module('kifi')

.directive('kfOrgProfileHeader', [
  '$state', '$http', '$location', 'modalService', 'orgProfileService',
  '$timeout', 'profileService', 'billingService', 'messageTicker',
  'ORG_PERMISSION', 'ORG_SETTING_VALUE',
  function ($state, $http, $location, modalService, orgProfileService,
            $timeout, profileService, billingService, messageTicker,
            ORG_PERMISSION, ORG_SETTING_VALUE) {

  return {
    restrict: 'A',
    scope: {
      profile: '=',
      viewer: '=',
      plan: '='
    },
    templateUrl: 'orgProfile/orgProfileHeader.tpl.html',
    link: function (scope) {
      scope.ORG_PERMISSION = ORG_PERMISSION;
      scope.ORG_SETTING_VALUE = ORG_SETTING_VALUE;
      scope.state = $state;

      scope.billingState = null;
      if (scope.viewer.permissions.indexOf(ORG_PERMISSION.MANAGE_PLAN) !== -1) {
        billingService
        .getBillingState(scope.profile.id)
        .then(function (billingState) {
          scope.billingState = billingState;
        });
      }

      var lastSavedInfo = {};

      var authToken = $location.search().authToken || '';
      scope.authTokenQueryString = authToken ? 'authToken='+authToken : '';

      scope.readonly = scope.viewer.permissions.indexOf(ORG_PERMISSION.EDIT_ORGANIZATION) === -1;
      scope.canViewMembers = scope.viewer.permissions.indexOf(ORG_PERMISSION.VIEW_MEMBERS) > -1;
      scope.acknowledgedInvite = false;
      scope.isKifiAdmin = profileService.me.experiments && profileService.me.experiments.indexOf('admin') > -1;


      scope.goToMemberInvite = function () {
        orgProfileService.trackEvent('user_clicked_page', scope.profile, { type: 'org_profile', action: 'invite' });
        scope.$emit('parentOpenInviteModal');
      };

      scope.undo = function () {
        scope.profile = angular.extend(scope.profile, lastSavedInfo);
      };

      var updateMe = function(data) {
        scope.profile = angular.extend(scope.profile, data);
        lastSavedInfo = angular.extend(lastSavedInfo, scope.profile);
      };

      scope.save = function () {
        var data = {
          name: scope.profile.name,
          site: scope.profile.site,
          description: scope.profile.description
        };

        return orgProfileService
          .updateOrgProfile(scope.profile.id, data)
          .then(function (res) {
            scope.$emit('trackOrgProfileEvent', 'click', {
              'type': 'orgProfile',
              'action': 'updateOrgProfile',
              'path': $location.path()
            });
            messageTicker({
              text: 'Saved Successfully',
              type: 'green'
            });
            return updateMe(res.data);
          })['catch'](function() {
            scope.undo();
            messageTicker({
              text: 'We\'re sorry. There was a problem saving your information. Please try again.',
              type: 'red'
            });
          });
      };

      scope.onClickTrack = function (event, action) {
        if (event.which === 1) {
          if (action === 'clickedAddCoverImage') {
            angular.element('.kf-oph-pic-file').click();
          }
          orgProfileService.trackEvent('user_clicked_page', scope.profile, { action: action });
        }
      };

      scope.onOrgProfileImageClick = function (event) { // doesn't look like we're using this anywhere, but if so, should be merged with onClickTrack
        if (event.which === 1) {
          angular.element('.kf-oph-pic-file').click();
          orgProfileService.trackEvent('user_clicked_page', scope.profile, { action: 'clickedAddCoverImage' });
        }
      };

      scope.onOrgProfileImageFileChosen = function (files) {
        var file = files[0];
        // if (file && /^image\/(?:jpeg|png|gif)$/.test(file.type)) {
        //   coverImageFile = file;
        //   console.log('LOADED FILE ', file);
        //   $timeout(readCoverImageFile);
        //   libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedCoverImageFile' });
        // } else {
        if (!(file && /^image\/(?:jpeg|png|gif)$/.test(file.type))) {
          modalService.openGenericErrorModal({
            modalData: {
              genericErrorMessage: 'Please choose a .jpg, .png or .gif file.'
            }
          });
        }
      };

      scope.shouldShowInviteBanner = function () {
        return profileService.userLoggedIn() && !scope.viewer.membership && scope.viewer.invite && !scope.acknowledgedInvite;
      };

      scope.shouldShowSignupBanner = function () {
        return !profileService.userLoggedIn() && !scope.viewer.membership && scope.viewer.invite && !angular.element('#kf-modal').length;
      };

      scope.canInvite = scope.viewer.permissions && scope.viewer.permissions.indexOf(ORG_PERMISSION.INVITE_MEMBERS) > -1;

      scope.inviteBannerButtons = [
        {
          label: 'Decline',
          className: 'kf-decline',
          click: function () {
            orgProfileService.declineOrgMemberInvite(scope.profile.id);
            scope.acknowledgedInvite = true;
          }
        },
        {
          label: 'Accept',
          className: 'kf-accept',
          click: function () {
            orgProfileService
              .acceptOrgMemberInvite(scope.profile.id, $location.search().authToken)
              .then(function () {
                scope.acknowledgedInvite = true;
                $state.reload('orgProfile');
              });
          }
        }
      ];

      scope.onClickUpsellMembers = function () {
        orgProfileService.trackEvent('user_clicked_page', scope.profile, { action: 'clickMembersUpsell' });
      };

      scope.onHoverUpsellMembers = function () {
        orgProfileService.trackEvent('user_viewed_page', scope.profile, { action: 'viewMembersUpsell' });
      };

      scope.onClickUpsellInvite = function () {
        orgProfileService.trackEvent('user_clicked_page', scope.profile, { action: 'clickInviteUpsell' });
      };

      scope.onHoverUpsellInvite = function () {
        orgProfileService.trackEvent('user_viewed_page', scope.profile, { action: 'viewInviteUpsell' });
      };

      scope.onCreditBannerUseIt = function () {
        if (scope.viewer.membership.role === 'admin') {
          $state.go('orgProfile.settings.plan', { upgrade: true });
        } else {
          modalService.open({
            template: 'billing/creditContactAdminsModal.tpl.html'
          });
        }
      };

      scope.onCreditBannerEarnMore = function () {
        $state.go('orgProfile.settings.credits');
      };

      scope.settingsRoute = scope.viewer.permissions.indexOf(ORG_PERMISSION.VIEW_SETTINGS) !== -1 ? 'orgProfile.settings.team' : 'orgProfile.settings.credits';
    }
  };
}]);
