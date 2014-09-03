'use strict';

angular.module('kifi')

.directive('kfSeeMutualFriends', [
  '$rootScope',
  '$timeout',
  'friendService',
  'inviteService',
  'savePymkService',
  function ($rootScope, $timeout, friendService, inviteService, savePymkService) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'friends/seeMutualFriends.tpl.html',
      link: function (scope, element/*, attrs*/) {
        scope.actionText = 'Add Friend';
        scope.clickable = true;

        // Helper function to update scope values based on passed-in person.
        function updateScopeValues (person) {
          scope.actionText = 'Add Friend';
          scope.clickable = true;

          scope.id = person.id;
          scope.name = person.fullName;
          scope.numMutualFriends = person.numMutualFriends;
          scope.pictureUrl = person.pictureUrl;

          // Divide up list of mutual friends into list of pairs of mutual
          // friends for two-column display in modal.
          var mutualFriendsPairs = [];
          var mutualFriendPair = [];
          person.mutualFriends.forEach(function (mutualFriend, index) {
            mutualFriend.pictureUrl = friendService.getPictureUrlForUser(mutualFriend);
            mutualFriendPair.push(mutualFriend);

            if (index % 2 !== 0) {
              mutualFriendsPairs.push(mutualFriendPair);
              mutualFriendPair = [];
            }

            // Flush last pair even if it has only one mutual friend.
            if ((index === person.mutualFriends.length - 1) && (mutualFriendPair.length > 0)) {
              mutualFriendsPairs.push(mutualFriendPair);
            }
          });

          scope.mutualFriendsPairs = mutualFriendsPairs;
        }

        // Retrieve a person you may know and his mutual friends,
        // and update the people displayed in the modal.
        updateScopeValues(savePymkService.getSavedPersonYouMayKnow());
        scope.$watch(function () {
          return savePymkService.getSavedPersonYouMayKnow();
        }, function (newVal/*, oldVal, scope*/) {
          updateScopeValues(newVal);
        });

        scope.addFriend = function (id) {
          if (!scope.clickable) {
            return;
          }
          scope.clickable = false;

          inviteService.friendRequest(id).then(function () {
            scope.actionText = 'Sent!';
            inviteService.expireSocialSearch();
          }, function () {
            scope.actionText = 'Error. Retry?';
            scope.clickable = true;
            inviteService.expireSocialSearch();
          });
        };

        // Force the modal scroll bar back up to the top.
        // A 100ms delay is inserted to wait for the Main controller
        // to respond to the 'showGlobalModal' event and display the
        // modal (we cannot scroll hidden elements).
        var mutualFriendsContainer = element.find('.kf-mutual-friends-friends');
        $rootScope.$on('showGlobalModal', function (e, modal) {
          if (modal === 'seeMutualFriends') {
            $timeout(function () {
              mutualFriendsContainer.scrollTop(0);
            }, 100);
          }
        });
      }
    };
  }
]);

