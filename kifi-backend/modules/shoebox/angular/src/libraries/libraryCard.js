'use strict';

angular.module('kifi')

.directive('kfLibraryCard', ['$FB', '$location', '$window', 'env', 'friendService', 'libraryService', 'modalService', 'profileService',
  function ($FB, $location, $window, env, friendService, libraryService, modalService, profileService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        username: '=',
        librarySlug: '=',
        recommendation: '=',
        loading: '='
      },
      templateUrl: 'libraries/libraryCard.tpl.html',
      link: function (scope, element/*, attrs*/) {
        scope.facebookAppId = $FB.appId();

        scope.clippedDescription = false;
        scope.followersToShow = 0;
        scope.numAdditionalFollowers = 0;


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

          if (_.isArray(scope.library.followers)) {
            // If we only have one additional follower that we can't fit in, then we can fit that one
            // in if we don't show the additional-number-of-followers circle.
            if (maxFollowersToShow === scope.library.followers.length - 1) {
              maxFollowersToShow++;
            }

            if (maxFollowersToShow >= scope.library.followers.length) {
              scope.followersToShow = scope.library.followers;
            } else {
              scope.followersToShow = scope.library.followers.slice(0, maxFollowersToShow);
              scope.numAdditionalFollowers = scope.library.followers.length - maxFollowersToShow;
            }
          }

          followerPicsDiv.width(maxFollowersToShow * widthPerFollowerPic);
        }

        // Data augmentation. May want to move out to own decorator service
        // like the keepDecoratorService if this gets too large.
        function augmentData() {
          // TODO(yiping): get real owner data when owner is not user.
          if (!scope.library.owner) {
            scope.library.owner = profileService.me;
          }

          // TODO(yiping): make sure recommended libraries have visibility.
          if (!scope.library.visibility && scope.recommendation) {
            scope.library.visibility = 'published';
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
            scope.library.shortDescription = scope.library.description.substr(0, maxLength);
            scope.clippedDescription = true;
          }

          scope.library.shareUrl = env.origin + scope.library.url;
          scope.library.shareText = 'Check out this Kifi library about ' + scope.library.name + '!';
        }

        scope.showLongDescription = function () {
          scope.clippedDescription = false;
        };

        scope.isUserLibrary = function (library) {
          // TODO(yiping): get recommendation libraries to have a "kind" property.
          return library.kind === 'user_created' || scope.recommendation;
        };

        scope.canBeShared = function (library) {
          // Only user created (i.e. not Main or Secret) libraries can be shared.
          // Of the user created libraries, public libraries can be shared by any Kifi user;
          // discoverable/secret libraries can be shared only by the library owner.

          // TODO(yiping): make sure that public libraries can be shared by any Kifi user.
          // This is not supported in the backend right now (10/1/2014).
          return scope.isUserLibrary(library) &&
                 (library.visibility === 'published' ||
                  library.owner.id === profileService.me.id);
        };

        scope.isMyLibrary = function (library) {
          return library.owner.id === profileService.me.id;
        };

        // TODO: determine this on the server side in the library response. For now, doing it client side.
        scope.followingLibrary = function (library) {
          var alreadyFollowing = _.some(scope.library.followers, {id: profileService.me.id});
          return !alreadyFollowing && library.owner.id !== profileService.me.id;
        };

        scope.followLibrary = function (library) {
          libraryService.joinLibrary(library.id);

          library.followers.push({
              id: profileService.me.id,
              firstName: profileService.me.firstName,
              lastName: profileService.me.lastName,
              pictureName: profileService.me.pictureName
            });

          augmentData();
          adjustFollowerPicsSize();
        };

        scope.unfollowLibrary = function (library) {
          libraryService.leaveLibrary(library.id).then(function () {
            _.remove(library.followers, function (follower) {
              return follower.id === profileService.me.id;
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

        // Wait until library is ready.
        scope.$watch('loading', function (newVal) {
          if (!newVal) {
            // For dev testing.
            // Uncomment the following to get some fake followers into the library.
            // scope.library.followers = [
            //   {
            //     id: '07170014-badc-4198-a462-6ba35d2ebb78',
            //     firstName: 'David',
            //     lastName: 'Elsonbaty',
            //     pictureName: 'EbOc0.jpg'
            //   },
            //   {
            //     id: '3ad31932-f3f9-4fe3-855c-3359051212e5',
            //     firstName: 'Danny',
            //     lastName: 'Blumenfeld',
            //     pictureName: 'VhYUF.jpg'
            //   },
            //   {
            //     id: '07170014-badc-4198-a462-6ba35d2ebb78',
            //     firstName: 'David',
            //     lastName: 'Elsonbaty',
            //     pictureName: 'EbOc0.jpg'
            //   },
            //   {
            //     id: '07170014-badc-4198-a462-6ba35d2ebb78',
            //     firstName: 'David',
            //     lastName: 'Elsonbaty',
            //     pictureName: 'EbOc0.jpg'
            //   },
            //   {
            //     id: '07170014-badc-4198-a462-6ba35d2ebb78',
            //     firstName: 'David',
            //     lastName: 'Elsonbaty',
            //     pictureName: 'EbOc0.jpg'
            //   },
            //   {
            //     id: '07170014-badc-4198-a462-6ba35d2ebb78',
            //     firstName: 'David',
            //     lastName: 'Elsonbaty',
            //     pictureName: 'EbOc0.jpg'
            //   },
            //   {
            //     id: '07170014-badc-4198-a462-6ba35d2ebb78',
            //     firstName: 'David',
            //     lastName: 'Elsonbaty',
            //     pictureName: 'EbOc0.jpg'
            //   },
            //   {
            //     id: '07170014-badc-4198-a462-6ba35d2ebb78',
            //     firstName: 'David',
            //     lastName: 'Elsonbaty',
            //     pictureName: 'EbOc0.jpg'
            //   },
            //   {
            //     id: '07170014-badc-4198-a462-6ba35d2ebb78',
            //     firstName: 'David',
            //     lastName: 'Elsonbaty',
            //     pictureName: 'EbOc0.jpg'
            //   }
            // ];

            augmentData();
            adjustFollowerPicsSize();
          }
        });

        var adjustFollowerPicsSizeOnResize = _.debounce(adjustFollowerPicsSize, 200);
        $window.addEventListener('resize', adjustFollowerPicsSizeOnResize);
        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', adjustFollowerPicsSizeOnResize);
        });

        scope.shareFB = function () {
          $FB.ui({
            method: 'share',
            href: scope.library.shareUrl
          });
        };
      }
    };
  }
]);
