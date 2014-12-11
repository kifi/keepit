'use strict';

angular.module('kifi')

.directive('kfRelatedLibraries', [
  'libraryService', 'routeService',
  function (libraryService, routeService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
      },
      templateUrl: 'libraries/relatedLibraries.tpl.html',
      link: function (scope/*, element, attrs*/) {
        function RelatedLibrary(library) {
          this.ownerFullName = library.owner.firstName + ' ' + library.owner.lastName;
          this.numFollowers = library.numFollowers;
          this.numKeeps = library.numKeeps;
          this.ownerPicUrl = routeService.formatPicUrl(library.owner.id, library.owner.picName, 200);
          this.name = library.name;
          this.primaryTopic = library.primaryTopic;
          this.imageUrl = 'https://djty7jcqog9qu.cloudfront.net/special-libs/l7SZ3gr3kUQJ.png';

          if (library.visibility === 'published') this.cardColor = 'green';
          else if (library.visibility === 'secret') this.cardColor = 'red';
          else this.cardColor = 'yellow';
          this.cardColor = '#935eb2';

        }

        libraryService.getRelatedLibraries(null).then(function (libraries) {
          scope.relatedLibraries = libraries.map(function (lib) {
            return new RelatedLibrary(lib);
          });
        });
      }
    };
  }
])

.directive('kfRelatedLibraryCard', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '='
      },
      templateUrl: 'libraries/relatedLibraryCard.tpl.html',
      link: function (/*scope, element, attrs*/) {
      }
    };
  }
])

;
