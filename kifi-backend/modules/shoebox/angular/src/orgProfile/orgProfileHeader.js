'use strict';

angular.module('kifi')

.directive('kfOrgProfileHeader', [
  '$state', '$http', '$analytics', '$location', 'modalService', 'orgProfileService', '$timeout',
  function ($state, $http, $analytics, $location, modalService, orgProfileService, $timeout) {

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

      scope.readonly = scope.membership.permissions.indexOf('edit_organization') === -1;
      scope.myTextValue = 'Hello';
      scope.acknowledgedInvite = false;

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
            // TODO (Adam): Should validate.
            // Success: sets last value to current one, shows success.
            // Error: Sets current value to last one, shows error.
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
        return scope.membership.isInvited && !scope.acknowledgedInvite;
      };

      scope.bannerButtons = [
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
                $state.reload();
              });
          }
        }
      ];
    }
  };
}]);
