'use strict';

angular.module('kifi')

.directive('kfLibraryCard', [
  '$FB', '$q', '$rootScope', '$timeout', '$twitter', 'env', 'friendService', 'libraryService',
  'modalService','profileService', 'routeService', 'util',
  function ($FB, $q, $rootScope, $timeout, $twitter, env, friendService, libraryService,
    modalService, profileService, routeService, util) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        followCallback: '&',
        clickLibraryCallback: '&'
      },
      templateUrl: 'libraries/libraryCard.tpl.html',
      link: function (scope) {

        //
        // Internal methods.
        //

        function trackShareEvent(action) {
          $timeout(function () {
            $rootScope.$emit('trackLibraryEvent', 'click', { action: action });
          });
        }

        // Data augmentation.
        // TODO(yiping): make new libraryDecoratorService to do this. Then, DRY up the code that is
        // currently in nav.js too.
        function augmentData() {

          // Figure out whether this library is a library that the user has been invited to.
          function getInvitePromise() {
            var promise = null;

            if (libraryService.invitedSummaries.length) {
              promise = $q.when(libraryService.invitedSummaries);
            } else {
              promise = libraryService.fetchLibrarySummaries(true).then(function () {
                return libraryService.invitedSummaries;
              });
            }

            return promise.then(function (invitedSummaries) {
              var maybeLib = _.find(invitedSummaries, { 'id' : scope.library.id });

              if (maybeLib) {
                return {
                  inviterName: maybeLib.inviter.firstName + ' ' + maybeLib.inviter.lastName,
                  actedOn: false
                };
              } else {
                return null;
              }
            });
          }

          // Libraries created with the extension do not have the description field.
          if (!scope.library.description) {
            scope.library.description = '';
          }

          if (scope.library.owner) {
            scope.library.owner.picUrl = friendService.getPictureUrlForUser(scope.library.owner);
            scope.library.owner.profileUrl = routeService.getProfileUrl(scope.library.owner.username);
          }

          scope.library.followers = scope.library.followers || [];
          scope.library.followers.forEach(augmentFollower);

          var maxLength = 150;
          if (scope.library.description.length > maxLength) {
            // Try to chop off at a word boundary, using a simple space as the word boundary delimiter.
            var clipLastIndex = maxLength;
            var lastSpaceIndex = scope.library.description.lastIndexOf(' ', maxLength);
            if (lastSpaceIndex !== -1) {
              clipLastIndex = lastSpaceIndex + 1;  // Grab the space too.
            }

            scope.library.shortDescription = util.processUrls(scope.library.description.substr(0, clipLastIndex));
            scope.clippedDescription = true;
          }
          scope.library.formattedDescription = '<p>' + util.processUrls(scope.library.description).replace(/\n+/g, '<p>');

          scope.library.shareUrl = env.origin + scope.library.url;
          scope.library.shareFbUrl = scope.library.shareUrl +
            '?utm_medium=vf_facebook&utm_source=library_share&utm_content=lid_' + scope.library.id +
            '&kcid=na-vf_facebook-library_share-lid_' + scope.library.id;

          scope.library.shareTwitterUrl = encodeURIComponent(scope.library.shareUrl +
            '?utm_medium=vf_twitter&utm_source=library_share&utm_content=lid_' + scope.library.id +
            '&kcid=na-vf_twitter-library_share-lid_' + scope.library.id);
          scope.library.shareText = 'Discover this amazing @Kifi library about ' + scope.library.name + '!';

          getInvitePromise().then(function (invite) {
            scope.library.invite = invite;
          });

          var image = scope.library.image;
          if (image) {
            scope.coverImageUrl = env.picBase + '/' + image.path;
          }
        }

        function augmentFollower(follower) {
          follower.picUrl = friendService.getPictureUrlForUser(follower);
          follower.profileUrl = routeService.getProfileUrl(follower.username);
          return follower;
        }

        function preloadSocial() {
          if (!$FB.failedToLoad && !$FB.loaded) {
            $FB.init();
          }
          if (!$twitter.failedToLoad && !$twitter.loaded) {
            $twitter.load();
          }
        }


        //
        // Scope methods.
        //
        scope.acceptInvitation = function () {
          scope.followLibrary();
        };

        scope.ignoreInvitation = function () {
          if (scope.library.invite) {
            libraryService.declineToJoinLibrary(scope.library.id).then(function () {
              scope.library.invite.actedOn = true;
            })['catch'](modalService.openGenericErrorModal);
          }
        };

        scope.showLongDescription = function () {
          scope.clippedDescription = false;
        };

        scope.shareFB = function () {
          trackShareEvent('clickedShareFacebook');
          $FB.ui({
            method: 'share',
            href: scope.library.shareFbUrl
          });
        };

        scope.shareTwitter = function () {
          trackShareEvent('clickedShareTwitter');
        };

        scope.alreadyFollowingLibrary = function () {
          return libraryService.isFollowingLibrary(scope.library);
        };

        scope.followLibrary = function () {
          scope.followCallback();
          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedFollowButton' });
          libraryService.joinLibrary(scope.library.id)['catch'](modalService.openGenericErrorModal);
        };

        scope.unfollowLibrary = function () {
          // TODO(yrl): ask Jen about whether we can remove this.
          libraryService.trackEvent('user_clicked_page', scope.library, { action: 'unfollow' });
          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedUnfollowButton' });
          libraryService.leaveLibrary(scope.library.id)['catch'](modalService.openGenericErrorModal);
        };

        scope.showFollowers = function () {
          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedViewFollowers' });

          modalService.open({
            template: 'libraries/libraryFollowersModal.tpl.html',
            modalData: {
              library: scope.library,
              currentPageOrigin: 'recommendationsPage'
            }
          });
        };


        //
        // Watches and listeners.
        //
        [
          $rootScope.$on('libraryKeepCountChanged', function (e, libraryId, keepCount) {
            if (libraryId === scope.library.id) {
              scope.library.numKeeps = keepCount;
            }
          }),
          $rootScope.$on('libraryJoined', function (e, libraryId) {
            var lib = scope.library;
            if (lib && libraryId === lib.id && lib.access === 'none') {
              (lib.invite || {}).actedOn = true;
              lib.access = 'read_only';
              lib.numFollowers++;
              var me = profileService.me;
              if (!_.contains(lib.followers, {id: me.id})) {
                lib.followers.push(augmentFollower(_.pick(me, 'id', 'firstName', 'lastName', 'pictureName', 'username')));
              }
            }
          }),
          $rootScope.$on('libraryLeft', function (e, libraryId) {
            var lib = scope.library;
            if (lib && libraryId === lib.id && lib.access !== 'none') {
              lib.access = 'none';
              lib.numFollowers--;
              _.remove(lib.followers, {id: profileService.me.id});
            }
          })
        ].forEach(function (deregister) {
          scope.$on('$destroy', deregister);
        });


        //
        // Initialize.
        //

        scope.clippedDescription = false;

        augmentData();

        $timeout(preloadSocial);
      }
    };
  }
]);
