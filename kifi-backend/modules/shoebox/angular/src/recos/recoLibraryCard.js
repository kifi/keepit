'use strict';

angular.module('kifi')

.directive('kfRecoLibraryCard', [
  '$FB', '$q', '$rootScope', '$timeout', '$twitter', 'env', 'libraryService',
  'modalService','profileService', 'util',
  function ($FB, $q, $rootScope, $timeout, $twitter, env, libraryService,
    modalService, profileService, util) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        followCallback: '&',
        clickLibraryCallback: '&'
      },
      templateUrl: 'recos/recoLibraryCard.tpl.html',
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

          // Libraries created with the extension do not have the description field.
          if (!scope.library.description) {
            scope.library.description = '';
          }

          var maxLength = 300, clipLength = 180;  // numbers differ significantly so that clicking More will show significantly more
          if (scope.library.description.length > maxLength) {
            // Try to chop off at a word boundary, using a simple space as the delimiter. Grab the space too.
            var clipLastIndex = scope.library.description.lastIndexOf(' ', clipLength) + 1 || clipLength;
            scope.library.shortDescription = util.linkify(scope.library.description.substr(0, clipLastIndex));
            scope.clippedDescription = true;
          }
          scope.library.formattedDescription = '<p>' + util.linkify(scope.library.description).replace(/\n+/g, '<p>');

          scope.library.shareUrl = env.origin + scope.library.url;
          scope.library.shareFbUrl = scope.library.shareUrl +
            '?utm_medium=vf_facebook&utm_source=library_share&utm_content=lid_' + scope.library.id +
            '&kcid=na-vf_facebook-library_share-lid_' + scope.library.id;

          scope.library.shareTwitterUrl = encodeURIComponent(scope.library.shareUrl +
            '?utm_medium=vf_twitter&utm_source=library_share&utm_content=lid_' + scope.library.id +
            '&kcid=na-vf_twitter-library_share-lid_' + scope.library.id);
          scope.library.shareText = 'Discover this amazing @Kifi library about ' + scope.library.name + '!';
        }


        //
        // Scope methods.
        //

        scope.showLongDescription = function () {
          scope.clippedDescription = false;
        };

        scope.preloadFB = function () {
          $FB.init();
        };

        scope.shareFB = function () {
          trackShareEvent('clickedShareFacebook');
          $FB.ui({
            method: 'share',
            href: scope.library.shareFbUrl
          });
        };

        scope.preloadTwitter = function () {
          $twitter.load();
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
              lib.access = 'read_only';
              lib.numFollowers++;
              var me = profileService.me;
              if (!_.contains(lib.followers, {id: me.id})) {
                lib.followers.push(_.pick(me, 'id', 'firstName', 'lastName', 'pictureName', 'username'));
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

        scope.Math = Math;
        scope.clippedDescription = false;

        augmentData();
      }
    };
  }
]);
