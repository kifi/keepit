'use strict';

angular.module('kifi')

.directive('kfCreateLibraryWidget', ['libraryService', 'util', 'profileService',
  function (libraryService, util, profileService) {
    return {
      restrict: 'A',
      scope: {
        onceLibraryCreated: '&',
        onExit: '&'
      },
      templateUrl: 'keep/createLibraryWidget.tpl.html',
      link: function (scope) {
        scope.me = profileService.me;
        scope.libraryProps = {};
        scope.newLibrary = { visibility: 'published' };
        scope.space = {};
        scope.$error = {};
        var submitting = false;

        scope.createLibrary = function (library) {
          if (submitting) {
            return;
          }

          scope.$error.name = libraryService.getLibraryNameError(library.name);
          if (scope.$error.name) {
            submitting = false;
            return;
          }
          scope.$error = {};

          // Create an owner object that declares the type (user/org) for backend.
          var owner;
          // If the location is an org
          if (scope.libraryProps.selectedOrgId) {
            owner = {
              org: scope.libraryProps.selectedOrgId
            };
          }

          library.slug = util.generateSlug(library.name);
          library.visibility = library.visibility || 'published';
          library.space = owner;

          libraryService.createLibrary(library, true).then(function (res) {
            scope.onceLibraryCreated()(res.data.library);
          })['catch'](function (err) {
            var error = err.data && err.data.error;
            switch (error) {
              case 'library name already exists for user':  // deprecated
              case 'library_slug_exists': // user can't edit slug, so try to get them to alter it via the name
              case 'library_name_exists':
              case 'invalid library name':  // deprecated
              case 'invalid_name':
                scope.$error.general = 'You already have a library with this name';
                break;
              default:
                scope.$error.general = 'Hmm, something went wrong. Try again later?';
                break;
            }
          })['finally'](function () {
            submitting = false;
          });
        };

        scope.$watch('newLibrary.name', function (newVal, oldVal) {
          if (newVal !== oldVal) {
            // Clear the error popover when the user changes the name field.
            scope.$error.name = null;
          }
        });
      }
    };
  }
]);
