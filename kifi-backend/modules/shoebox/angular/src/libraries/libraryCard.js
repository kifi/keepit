'use strict';

angular.module('kifi')

.directive('kfLibraryCard', ['friendService',
  function (friendService) {
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
        if (scope.library.owner) {
          scope.library.owner.image = friendService.getPictureUrlForUser(scope.library.owner);
        }

        scope.library.followers.forEach(function (follower) {
          follower.image = friendService.getPictureUrlForUser(follower);
        });
      }
    };
  }
]);
