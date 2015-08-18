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
      var lastSavedInfo = {};
      scope.notification = null;

      var authToken = $location.search().authToken || '';

      scope.readonly = scope.membership.permissions.indexOf('edit_organization') === -1;
      scope.myTextValue = 'Hello';
      scope.acknowledgedInvite = false;

      if (!profileService.userLoggedIn() && scope.profile && authToken) {
        signupService.register({orgId: scope.profile.id, intent: 'joinOrg', orgAuthToken: authToken, invite: scope.membership.invite});
      }

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

      scope.onOrgProfileImageClick = function (event) {
        if (event.which === 1) {
          angular.element('.kf-oph-pic-file').click();
          //libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedAddCoverImage' });
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
        // TODO: Check if this user is a member already
        return profileService.userLoggedIn() && scope.membership.isInvited && !scope.acknowledgedInvite;
      };

      scope.shouldShowSignupBanner = function () {
        return !profileService.userLoggedIn() && !!scope.membership.invite;
      };

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
