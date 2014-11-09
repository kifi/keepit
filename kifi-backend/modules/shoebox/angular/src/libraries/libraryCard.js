'use strict';

angular.module('kifi')

.directive('kfLibraryCard', [
  '$FB', '$location', '$q', '$rootScope', '$window', 'env', 'friendService', 'libraryService', 'modalService',
  'profileService', 'platformService', 'signupService', '$twitter',
  function ($FB, $location, $q, $rootScope, $window, env, friendService, libraryService, modalService,
    profileService, platformService, signupService, $twitter) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        username: '=',
        librarySlug: '=',
        recommendation: '=',
        loading: '=',
        toggleEdit: '='
      },
      templateUrl: 'libraries/libraryCard.tpl.html',
      link: function (scope, element/*, attrs*/) {
        //
        // Scope data.
        //
        scope.isUserLoggedOut = $rootScope.userLoggedIn === false;
        scope.clippedDescription = false;
        scope.followersToShow = 0;
        scope.numAdditionalFollowers = 0;
        scope.editKeepsText = 'Edit Keeps';

        var magicImages = {
          'l7SZ3gr3kUQJ': '//djty7jcqog9qu.cloudfront.net/special-libs/l7SZ3gr3kUQJ.png',
          'l4APrlM5wzaM': '//djty7jcqog9qu.cloudfront.net/special-libs/l4APrlM5wzaM.png',
          'l2iJXRO7vtoa': '//djty7jcqog9qu.cloudfront.net/special-libs/l2iJXRO7vtoa.png',
          'l292wb07mhuB': '//djty7jcqog9qu.cloudfront.net/special-libs/l292wb07mhuB.png',
          'lGcw3PhnD9Wo': '//djty7jcqog9qu.cloudfront.net/special-libs/lGcw3PhnD9Wo.png',
          'l3ai2ejn5t9L': '//djty7jcqog9qu.cloudfront.net/special-libs/l3ai2ejn5t9L.png',
          'lzgAqPcczp5J': '//djty7jcqog9qu.cloudfront.net/special-libs/lzgAqPcczp5J.png',
          'l14bTasWaiYK': '//djty7jcqog9qu.cloudfront.net/special-libs/l14bTasWaiYK.png',
          'l5ooCseWZXla': '//djty7jcqog9qu.cloudfront.net/special-libs/l5ooCseWZXla.png',
          'lFiSQapwp732': '//djty7jcqog9qu.cloudfront.net/special-libs/lFiSQapwp732.png',
          'lGWrqQb9JsbJ': '//djty7jcqog9qu.cloudfront.net/special-libs/lGWrqQb9JsbJ.png',
          'lCaeGbBOh5YT': '//djty7jcqog9qu.cloudfront.net/special-libs/lCaeGbBOh5YT.png',
          'lEc2xD0eNU9f': '//djty7jcqog9qu.cloudfront.net/special-libs/lEc2xD0eNU9f.png',
          'l8SVuYHq9Qo5': '//djty7jcqog9qu.cloudfront.net/special-libs/l8SVuYHq9Qo5.jpg',
          'l5jqAsWp5j8Y': '//djty7jcqog9qu.cloudfront.net/special-libs/l5jqAsWp5j8Y.jpg',
          'l8zOB62bja1e': '//djty7jcqog9qu.cloudfront.net/special-libs/l8zOB62bja1e.jpg',
          'l0XQspLziYol': '//djty7jcqog9qu.cloudfront.net/special-libs/l0XQspLziYol.jpg'
        };
        scope.magicImage = magicImages[scope.library.id];


        //
        // Internal methods.
        //
        function adjustFollowerPicsSize() {
          var statsAndFollowersDiv = element.find('.kf-keep-lib-stats-and-followers');
          var followerPicsDiv = element.find('.kf-keep-lib-follower-pics');
          var widthPerFollowerPic = 50;

          var parentWidth = statsAndFollowersDiv.width();

          // 250px needed for other stuff in the parent's width.
          var maxFollowersToShow = Math.floor((parentWidth - 250) / widthPerFollowerPic);
          scope.numAdditionalFollowers = 0;

          // If we only have one additional follower that we can't fit in, then we can fit that one
          // in if we don't show the additional-number-of-followers circle.
          if (maxFollowersToShow === scope.library.numFollowers - 1) {
            maxFollowersToShow++;
          }

          scope.$evalAsync(function () {
            if (maxFollowersToShow < 1) {
              scope.followersToShow = [];
              scope.numAdditionalFollowers = scope.library.numFollowers;
            } else if (maxFollowersToShow >= scope.library.numFollowers) {
              scope.followersToShow = scope.library.followers;
            } else {
              scope.followersToShow = scope.library.followers.slice(0, maxFollowersToShow);
              scope.numAdditionalFollowers = scope.library.numFollowers - maxFollowersToShow;
            }

            followerPicsDiv.width(maxFollowersToShow >= 1 ? maxFollowersToShow * widthPerFollowerPic : 0);
          });
        }

        // Data augmentation.
        // TODO(yiping): make new libraryDecoratorService to do this. Then, DRY up the code that is
        // currently in nav.js too.
        function augmentData() {
          // TODO(yiping): get real owner data when owner is not user.
          if (!scope.library.owner) {
            scope.library.owner = profileService.me;
          }

          if (scope.library.owner) {
            scope.library.owner.picUrl = friendService.getPictureUrlForUser(scope.library.owner);
          }

          scope.library.followers = scope.library.followers || [];
          scope.library.followers.forEach(function (follower) {
            follower.picUrl = friendService.getPictureUrlForUser(follower);
          });

          var maxLength = 150;
          scope.library.formattedDescription = '<p>' + angular.element('<div>').text(scope.library.description).text().replace(/\n+/, '<p>');

          if (scope.library.description && scope.library.description.length > maxLength && !scope.isUserLoggedOut) {
            // Try to chop off at a word boundary, using a simple space as the word boundary delimiter.
            var clipLastIndex = maxLength;
            var lastSpaceIndex = scope.library.description.lastIndexOf(' ', maxLength);
            if (lastSpaceIndex !== -1) {
              clipLastIndex = lastSpaceIndex + 1;  // Grab the space too.
            }

            scope.library.shortDescription = scope.library.description.substr(0, clipLastIndex);
            scope.clippedDescription = true;
          }

          scope.library.shareUrl = env.origin + scope.library.url;
          scope.library.shareFbUrl = scope.library.shareUrl +
            '?utm_medium=vf_facebook&utm_source=library_invite&utm_content=lid_' + scope.library.id +
            '&kcid=na-vf_facebook-library_invite-lid_' + scope.library.id;

          scope.library.shareTwitterUrl = encodeURIComponent(scope.library.shareUrl +
            '?utm_medium=vf_twitter&utm_source=library_invite&utm_content=lid_' + scope.library.id +
            '&kcid=na-vf_twitter-library_invite-lid_' + scope.library.id);
          scope.library.shareText = 'Discover this amazing @Kifi library about ' + scope.library.name + '!';

          // Figure out whether this library is a library that the user has been invited to.
          // If so, display an invite header.
          var promise = null;
          if (libraryService.invitedSummaries.length) {
            promise = $q.when(libraryService.invitedSummaries);
          } else {
            promise = libraryService.fetchLibrarySummaries(true).then(function () {
              return libraryService.invitedSummaries;
            });
          }

          promise.then(function (invitedSummaries) {
            var maybeLib = _.find(invitedSummaries, { 'id' : scope.library.id });
            if (maybeLib) {
              scope.library.invite = {
                inviterName: maybeLib.inviter.firstName + ' ' + maybeLib.inviter.lastName,
                actedOn: false
              };
            }
          });

          if (scope.$root.userLoggedIn === false) {
            scope.$evalAsync(function () {
              angular.element('.white-background').height(element.height() + 20);
            });
          }
        }

        function preloadSocial () {
          if (!$FB.failedToLoad && !$FB.loaded) {
            $FB.init();
          }
          if (!$twitter.failedToLoad && !$twitter.loaded) {
            $twitter.load();
          }
        }
        scope.$evalAsync(preloadSocial);


        //
        // Scope methods.
        //
        scope.acceptInvitation = function (library) {
          scope.followLibrary(library);
        };

        scope.ignoreInvitation = function (library) {
          if (library.invite) {
            library.invite.actedOn = true;
            libraryService.declineToJoinLibrary(library.id);
          }
        };

        scope.showLongDescription = function () {
          scope.clippedDescription = false;
        };

        scope.isUserLibrary = function (library) {
          return library.kind === 'user_created';
        };

        scope.isMyLibrary = function (library) {
          return library.owner && library.owner.id === profileService.me.id;
        };

        scope.canBeShared = function (library) {
          // Only user created (i.e. not Main or Secret) libraries can be shared.
          // Of the user created libraries, public libraries can be shared by any Kifi user;
          // discoverable/secret libraries can be shared only by the library owner.
          return !scope.isUserLoggedOut && scope.isUserLibrary(library) &&
                 (library.visibility === 'published' ||
                  scope.isMyLibrary(library));
        };

        scope.isPublic = function (library) {
          return library.visibility === 'published';
        };

        scope.shareFB = function () {
          $FB.ui({
            method: 'share',
            href: scope.library.shareFbUrl
          });
        };

        // TODO: determine this on the server side in the library response. For now, doing it client side.
        scope.canFollowLibrary = function (library) {
          return !scope.alreadyFollowingLibrary(library) && !scope.isMyLibrary(library);
        };

        scope.alreadyFollowingLibrary = function (library) {
          return (library.access && (library.access === 'read_only')) ||
            (_.some(libraryService.librarySummaries, { id: library.id }) && !scope.isMyLibrary(library));
        };

        scope.followLibrary = function (library) {
          if (library.invite) {
            library.invite.actedOn = true;
          }

          if (platformService.isSupportedMobilePlatform()) {
            platformService.goToAppOrStore($location.absUrl());
            return;
          } else if ($rootScope.userLoggedIn === false) {
            return signupService.register({libraryId: scope.library.id});
          }

          libraryService.joinLibrary(library.id).then(function (result) {
            if (result === 'already_joined') {
              scope.genericErrorMessage = 'You are already following this library!';
              modalService.open({
                template: 'common/modal/genericErrorModal.tpl.html',
                scope: scope
              });
              return;
            }

            library.followers.push({
              id: profileService.me.id,
              firstName: profileService.me.firstName,
              lastName: profileService.me.lastName,
              pictureName: profileService.me.pictureName
            });

            augmentData();
            adjustFollowerPicsSize();
          });
        };

        scope.unfollowLibrary = function (library) {
          libraryService.leaveLibrary(library.id);
        };

        scope.manageLibrary = function () {
          modalService.open({
            template: 'libraries/manageLibraryModal.tpl.html',
            modalData: {
              pane: 'manage',
              library: scope.library,
              returnAction: function () {
                libraryService.getLibraryById(scope.library.id, true).then(function (data) {
                  libraryService.getLibraryByUserSlug(scope.username, data.library.slug, true).then(function (library) {
                    _.assign(scope.library, library);
                    augmentData();
                    adjustFollowerPicsSize();
                  });

                  if (data.library.slug !== scope.librarySlug) {
                    $location.path('/' + scope.username + '/' + data.library.slug);
                  }
                });
              }
            }
          });
        };

        scope.toggleEditKeeps = function () {
          scope.toggleEdit();
          scope.editKeepsText = scope.editKeepsText === 'Edit Keeps' ? 'Done Editing' : 'Edit Keeps';
        };

        scope.showFollowers = function () {
          if (scope.library.owner.id === profileService.me.id) {
            modalService.open({
              template: 'libraries/manageLibraryModal.tpl.html',
              modalData: {
                pane: 'members',
                library: scope.library
              }
            });
          } else {
            modalService.open({
              template: 'libraries/libraryFollowersModal.tpl.html',
              modalData: {
                library: scope.library
              }
            });
          }

        };

        //
        // Watches and listeners.
        //

        // Wait until library data is ready before processing information to display the library card.
        scope.$watch('loading', function (newVal) {
          if (!newVal) {
            augmentData();
            adjustFollowerPicsSize();
          }
        });

        var deregisterLibraryUpdated = $rootScope.$on('libraryUpdated', function (e, library) {
          if (library.id === scope.library.id) {
            _.assign(scope.library, library);
            augmentData();
            adjustFollowerPicsSize();
          }
        });
        scope.$on('$destroy', deregisterLibraryUpdated);

        // Update how many follower pics are shown when the window is resized.
        var adjustFollowerPicsSizeOnResize = _.debounce(adjustFollowerPicsSize, 200);
        $window.addEventListener('resize', adjustFollowerPicsSizeOnResize);
        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', adjustFollowerPicsSizeOnResize);
        });
      }
    };
  }
]);
