'use strict';

angular.module('kifi')

.directive('kfRecoLibraryCard', [
  '$FB', '$q', '$rootScope', '$timeout', '$twitter', 'env', 'libraryService',
  'modalService','profileService', 'URI', 'linkify',
  function ($FB, $q, $rootScope, $timeout, $twitter, env, libraryService,
    modalService, profileService, URI, linkify) {
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

        // Data augmentation.
        function augmentData() {
          var maxLength = 300, clipLength = 180;  // numbers differ significantly so that clicking More will show significantly more
          var description = scope.library.description || '';
          if (description.length > maxLength) {
            // Try to chop off at a word boundary, using a simple space as the delimiter. Grab the space too.
            var clipLastIndex = description.lastIndexOf(' ', clipLength) + 1 || clipLength;
            scope.library.shortDescription = linkify(description.substr(0, clipLastIndex));
            scope.clippedDescription = true;
          }
          scope.library.formattedDescription = '<p>' + linkify(description).replace(/\n+/g, '<p>');

          scope.library.absUrl = env.origin + scope.library.url;
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
          libraryService.trackEvent('user_clicked_page', scope.library, { type: 'recommendations', action: 'clickedShareFacebook'});
          $FB.ui({
            method: 'share',
            href: scope.library.absUrl +
              '?utm_medium=vf_facebook&utm_source=library_share&utm_content=lid_' + scope.library.id +
              '&kcid=na-vf_facebook-library_share-lid_' + scope.library.id
          });
        };

        scope.preloadTwitter = function () {
          $twitter.load();
        };

        scope.shareTwitter = function (event) {
          libraryService.trackEvent('user_clicked_page', scope.library, { type: 'recommendations', action: 'clickedShareTwitter'});
          event.target.href = 'https://twitter.com/intent/tweet' + URI.formatQueryString({
            original_referer: scope.library.absUrl,
            text: 'Discover this amazing @Kifi library about ' + scope.library.name + '!',
            tw_p: 'tweetbutton',
            url: scope.library.absUrl +
              '?utm_medium=vf_twitter&utm_source=library_share&utm_content=lid_' + scope.library.id +
              '&kcid=na-vf_twitter-library_share-lid_' + scope.library.id
          });
        };

        scope.followLibrary = function () {
          scope.followCallback();
          libraryService.trackEvent('user_clicked_page', scope.library, { type: 'recommendations', action: 'clickedFollowButton'});
          libraryService.joinLibrary(scope.library.id)['catch'](modalService.openGenericErrorModal);
        };

        scope.unfollowLibrary = function () {
          libraryService.trackEvent('user_clicked_page', scope.library, { type: 'recommendations', action: 'clickedUnfollowButton'});
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
          $rootScope.$on('libraryJoined', function (e, libraryId, membership) {
            var lib = scope.library;
            if (lib && libraryId === lib.id && lib.access === 'none') {
              lib.access = membership.access;
              lib.numFollowers++;  // TODO: handle join as collaborator properly
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
