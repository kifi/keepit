'use strict';

angular.module('kifi')

.directive('kfLibraryCard', ['friendService', 'libraryService', 'profileService',
  function (friendService, libraryService, profileService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '='
      },
      templateUrl: 'libraries/libraryCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
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
        //   }
        // ];

        // Data augmentation. May want to move out to own decorator service
        // like the keepDecoratorService.

        function augmentData() {
          if (scope.library.owner) {
            scope.library.owner.image = friendService.getPictureUrlForUser(scope.library.owner);
          }

          scope.library.followers.forEach(function (follower) {
            follower.image = friendService.getPictureUrlForUser(follower);
          });
        }

        scope.followed = function () {
          return _.some(scope.library.followers, function (follower) {
            return follower.id === profileService.me.id;
          });
        };

        scope.follow = function () {
          if (!scope.followed()) {
            libraryService.joinLibrary(scope.library.id);
            scope.library.followers.push({
              id: profileService.me.id,
              firstName: profileService.me.firstName,
              lastName: profileService.me.lastName,
              pictureName: profileService.me.pictureName
            });
            augmentData();
          }
        };

        scope.unfollow = function () {
          libraryService.leaveLibrary(scope.library.id).then( function () {
            _.remove(scope.library.followers, function (follower) {
              return follower.id === profileService.me.id;
            });
          });
        };

        augmentData();


      }
    };
  }
]);
