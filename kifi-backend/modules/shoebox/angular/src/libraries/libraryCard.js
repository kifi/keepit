'use strict';

angular.module('kifi')

.directive('kfLibraryCard', [
  '$FB', '$location', '$rootScope', '$window', 'env', 'friendService', 'libraryService', 'modalService', 'profileService', 'platformService',
  function ($FB, $location, $rootScope, $window, env, friendService, libraryService, modalService, profileService, platformService) {
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
        scope.facebookAppId = $FB.appId();
        scope.clippedDescription = false;
        scope.followersToShow = 0;
        scope.numAdditionalFollowers = 0;
        scope.editKeepsText = 'Edit Keeps';


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

          if (_.isArray(scope.library.followers)) {
            scope.library.followers.forEach(function (follower) {
              follower.picUrl = friendService.getPictureUrlForUser(follower);
            });
          }

          var maxLength = 150;
          if (scope.library.description && scope.library.description.length > maxLength) {
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
          scope.library.shareText = 'Check out this Kifi library about ' + scope.library.name + '!';
        }


        //
        // Scope methods.
        //
        scope.showLongDescription = function () {
          scope.clippedDescription = false;
        };

        scope.isUserLibrary = function (library) {
          return library.kind === 'user_created';
        };

        scope.isMyLibrary = function (library) {
          return library.owner && library.owner.id === profileService.me.id;
        };

        scope.followerIsMe = function (follower) {
          return follower.id === profileService.me.id;
        };

        scope.canBeShared = function (library) {
          // Only user created (i.e. not Main or Secret) libraries can be shared.
          // Of the user created libraries, public libraries can be shared by any Kifi user;
          // discoverable/secret libraries can be shared only by the library owner.
          return scope.isUserLibrary(library) &&
                 (library.visibility === 'published' ||
                  scope.isMyLibrary(library));
        };

        scope.shareFB = function () {
          $FB.ui({
            method: 'share',
            href: scope.library.shareUrl
          });
        };

        // TODO: determine this on the server side in the library response. For now, doing it client side.
        scope.canFollowLibrary = function (library) {
          return !scope.alreadyFollowingLibrary(library) && !scope.isMyLibrary(library);
        };

        scope.alreadyFollowingLibrary = function (library) {
          return (library.access && (library.access === 'read_only')) || _.some(library.followers, { id: profileService.me.id });
        };

        scope.followLibrary = function (library) {
          if (platformService.isSupportedMobilePlatform()) {
            platformService.goToAppOrStore($location.absUrl());
            return;
          }
          libraryService.joinLibrary(library.id).then(function (result) {
            if (result === 'already_joined') {
              // TODO(yiping): make a better error message. One idea is to update
              // the current generic error modal to take in a message parameter.
              $window.alert('You are already following this library!');
              return;
            }

            // Let the user be the first one listed. :)
            library.followers.unshift({
              id: profileService.me.id,
              firstName: profileService.me.firstName,
              lastName: profileService.me.lastName,
              pictureName: profileService.me.pictureName
            });

            libraryService.fetchLibrarySummaries(true).then(function () {
              $rootScope.$emit('librarySummariesChanged');
              augmentData();
              adjustFollowerPicsSize();
            });
          });
        };

        scope.unfollowLibrary = function (library) {
          libraryService.leaveLibrary(library.id).then(function () {
            libraryService.fetchLibrarySummaries(true).then(function () {
              $rootScope.$emit('librarySummariesChanged');

              // Note: no need to augmentData for unfollowed library.
              adjustFollowerPicsSize();
            });
          });
        };

        scope.manageLibrary = function () {
          modalService.open({
            template: 'libraries/manageLibraryModal.tpl.html',
            modalData: {
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

        $rootScope.$on('libraryUpdated', function (e, library) {
          _.assign(scope.library, library);
          augmentData();
          adjustFollowerPicsSize();
        });

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
