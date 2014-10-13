'use strict';

angular.module('kifi')

.directive('kfManageLibrary', [
  '$location', '$window', '$rootScope', 'friendService', 'libraryService', 'profileService', 'util',
  function ($location, $window, $rootScope, friendService, libraryService, profileService, util) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'libraries/manageLibrary.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {
        //
        // Internal data.
        //
        var nameInput = element.find('.manage-lib-name-input');
        var returnAction = null;
        var submitting = false;


        //
        // Scope data.
        //
        scope.username = profileService.me.username;
        scope.userHasEditedSlug = false;
        scope.$error = {};

        scope.visibility = {
          'published': {
            'title': 'Published Library',
            'content': 'This library is available for everyone to see. It also generates a dynamic public page to share with non-Kifi users.'
          },
          'secret': {
            'title': 'Secret Library',
            'content': 'This library is visible only to you and people you invite.'
          },
          'discoverable': {
            'title': 'Discoverable Library',
            'content': 'This library can surface in searches conducted by your Kifi friends.'
          }
        };


        //
        // Scope methods.
        //
        scope.close = function () {
          kfModalCtrl.close();
        };

        scope.editSlug = function () {
          scope.userHasEditedSlug = !!scope.library.slug;
          scope.library.slug = util.generateSlug(scope.library.slug);
        };

        scope.saveLibrary = function () {
          if (submitting) {
            return;
          }

          if (scope.library.name.length < 3) {
            scope.$error.name = 'Try a longer name';
            return;
          }

          submitting = true;
          var promise;
          if (scope.modifyingExistingLibrary && scope.library.id) {
            promise = libraryService.modifyLibrary(scope.library);
          } else {
            promise = libraryService.createLibrary(scope.library);
          }

          promise.then(function (resp) {
            scope.$error = {};

            submitting = false;

            libraryService.fetchLibrarySummaries(true).then(function () {
              $rootScope.$emit('changedLibrary');
              scope.close();

              if (!returnAction) {
                $location.path(resp.data.url);
              } else {
                returnAction();
              }
            });
          })['catch'](function (err) {
            submitting = false;

            var error = err.data && err.data.error;
            switch (error) {
              case 'library name already exists for user':  // deprecated
              case 'library_name_exists':
                scope.$error.general = 'You already have a library with this name. Pick another.';
                break;
              case 'invalid library name':  // deprecated
              case 'invalid_name':
                scope.$error.general = 'You already have a library with this name. Pick another.';
                break;
              case 'library_slug_exists':
                scope.$error.general = 'You already have a library with the same URL. Pick another.';
                break;
              case 'invalid library slug':  // deprecated
              case 'invalid_slug':
                scope.$error.general = 'The URL you picked isn\'t valid. Try using only letters and numbers.';
                break;
              default:
                scope.close();
                break;
            }
          });
        };

        scope.deleteLibrary = function () {
          if (submitting) {
            return;
          }

          var confirmDelete = $window.confirm('Are you sure you want to delete this library? This action cannot be undone.');

          if (confirmDelete) {
            submitting = true;

            libraryService.deleteLibrary(scope.library.id).then(function () {
              $rootScope.$emit('changedLibrary');
              scope.close();
              $location.path('/');
            });
          }
        };


        //
        // Watches and listeners.
        //
        scope.$watch(function () {
          return scope.library.name;
        }, function (newVal, oldVal) {
          if (newVal !== oldVal) {
            scope.userHasEditedSlug = !!scope.library.name;
            scope.library.slug = util.generateSlug(newVal);
          }
        });


        //
        // On link.
        //
        if (scope.modalData) {
          scope.library = _.cloneDeep(scope.modalData.library);
          returnAction = scope.modalData.returnAction || null;
          scope.modifyingExistingLibrary = true;
          scope.userHasEditedSlug = true;
          scope.modalTitle = scope.library.name;
        } else {
          scope.library = {
            'name': '',
            'description': '',
            'slug': '',

            // By default, the create library form selects the "discoverable" visiblity for a new library.
            'visibility': 'discoverable'
          };
          scope.modalTitle = 'Create a library';
        }

        nameInput.focus();
      }
    };
  }
]);
