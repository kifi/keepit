'use strict';

angular.module('kifi')

.directive('kfOrgProfileHeader', [
  '$state', '$http', '$analytics', '$location', 'modalService', 'orgProfileService', '$timeout', 'profileService', 'signupService',
  function ($state, $http, $analytics, $location, modalService, orgProfileService, $timeout, profileService, signupService) {

  return {
    restrict: 'A',
    scope: {
      profile: '=',
      membership: '='
    },
    templateUrl: 'orgProfile/orgProfileHeader.tpl.html',
    link: function (scope) {
      scope.state = $state;

      var lastSavedInfo = {};
      scope.notification = null;

      var authToken = $location.search().authToken || '';
      scope.authTokenQueryString = authToken ? 'authToken='+authToken : '';

      scope.readonly = scope.membership.permissions.indexOf('edit_organization') === -1;
      scope.myTextValue = 'Hello';
      scope.acknowledgedInvite = false;
      scope.showAdminLink = profileService.me.experiments && profileService.me.experiments.indexOf('admin') > -1;

      if (!profileService.userLoggedIn() && scope.profile && scope.membership.invite) {
        signupService.register({orgId: scope.profile.id, intent: 'joinOrg', orgAuthToken: authToken, invite: scope.membership.invite});
      }

      scope.goToMemberInvite = function () {
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
            $analytics.eventTrack('user_clicked_page', {
              'action': 'updateOrgProfile',
              'path': $location.path()
            });
            scope.notification = 'save';
            $timeout(function() {
              scope.notification = null;
            }, 1500);
            return updateMe(res.data);
          })['catch'](function() {
            scope.notification = 'error';
            scope.undo();
            $timeout(function() {
              scope.notification = null;
            }, 1500);
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
        return profileService.userLoggedIn() && !scope.membership.role && scope.membership.invite && !scope.acknowledgedInvite;
      };

      scope.shouldShowSignupBanner = function () {
        return !profileService.userLoggedIn() && !scope.membership.role && scope.membership.invite && !angular.element('#kf-modal').length;
      };

      scope.canInvite = scope.membership.permissions && scope.membership.permissions.indexOf('invite_members') > -1;

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
                orgProfileService.invalidateOrgProfileCache();
                $state.reload();
              });
          }
        }
      ];

      scope.signupBannerButtons = [
        {
          label: 'Accept',
          className: 'kf-accept',
          click: function () {
            signupService.register({orgId: scope.profile.id, intent: 'joinOrg', orgAuthToken: authToken, invite: scope.membership.invite });
          }
        }
      ];
    }
  };
}]);
