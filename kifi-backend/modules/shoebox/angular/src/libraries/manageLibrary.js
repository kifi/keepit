'use strict';

angular.module('kifi')

.directive('kfManageLibrary', ['libraryService', 'profileService',
  function (libraryService, profileService) {
    return {
      restrict: 'A',
      scope: {},
      require: '^kfModal',
      templateUrl: 'libraries/manageLibrary.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {
        //
        // Internal data.
        //


        //
        // Scope data.
        //
        scope.username = profileService.me.username;


        //
        // Internal methods.
        //


        //
        // Scope methods.
        //
        scope.close = function () {
          kfModalCtrl.close();
        };


        //
        // On link.
        //
        if (libraryService.libraryState.library) {
          scope.library = _.cloneDeep(libraryService.libraryState.library);
          scope.modalTitle = scope.library.name;
        } else {
          scope.library = {
            'name': '',
            'visibility': 'discoverable',
            'description': '',
            'slug': ''
          };
          scope.modalTitle = 'Create a library';
        }
      }
    };
  }
])


.controller('ManageLibraryCtrl', [
  '$scope', '$routeParams', 'libraryService', 'util', '$timeout', '$location', 'profileService',
  function ($scope, $routeParams, libraryService, util, $timeout, $location, profileService) {
    $scope.$error = {};
    $scope.userHasEditedSlug = false;
    $scope.username = profileService.me.username;
    var returnAction;

    if (libraryService.libraryState.library) {
      $scope.modifyingExistingLibrary = true;
      $scope.library = _.cloneDeep(libraryService.libraryState.library);
      returnAction = libraryService.libraryState.returnAction || null;
      libraryService.libraryState = {};
      $scope.modalTitle = $scope.library.name;
      $scope.userHasEditedSlug = true;
    } else {
      $scope.library = {
        'name': '',
        'visibility': 'discoverable',
        'description': '',
        'slug': ''
      };
      $scope.modalTitle = 'Create a library';
    }

    var generateSlug = function (name) {
      return name.toLowerCase().replace(/[^\w\s]|_/g, '').replace(/\s+/g, '-').replace(/^-/, '');
    };

    $scope.$watch(function() {
      return $scope.library.name;
    }, function (v) {
      if (!$scope.userHasEditedSlug) {
        $scope.library.slug = generateSlug(v);
      }
    });

    $scope.editSlug = function () {
      $scope.userHasEditedSlug = true;
      $scope.library.slug = generateSlug($scope.library.slug);
    };


    $scope.saveLibrary = function () {
      var newError = false;
      if ($scope.library.name.length < 3) {
        $scope.$error.name = ' Try a longer name';
        newError = true;
      }
      if (newError || $scope.submitting) {
        return;
      }
      $scope.submitting = true;
      var promise;
      if ($scope.modifyingExistingLibrary && $scope.library.id) {
        // Save existing library
        promise = libraryService.modifyLibrary($scope.library);
      } else {
        promise = libraryService.createLibrary($scope.library);
      }
      promise.then(function (resp) {
          $scope.$error = {};
          $scope.submitting = false;
          libraryService.fetchLibrarySummaries(true);
          // There must be a better way:
          $scope.hideModal();
          $scope.modal = null;

          if (!returnAction) {
            $location.path(resp.url);
          } else {
            returnAction(resp);
          }
      })['catch'](function (err) {
        $scope.submitting = false;
        var error = err.data && err.data.error;
        switch (error) {
          case 'library name already exists for user': // deprecated
          case 'library_name_exists':
            $scope.$error.general = 'You already have a library with this name. Pick another.';
            break;
          case 'invalid library name': // deprecated
          case 'invalid_name':
            $scope.$error.general = 'You already have a library with this name. Pick another.';
            break;
          case 'library_slug_exists':
            $scope.$error.general = 'You already have a library with the same URL. Pick another.';
            break;
          case 'invalid library slug': // deprecated
          case 'invalid_slug':
            $scope.$error.general = 'The URL you picked isn\'t valid. Try using only letters and numbers.';
            break;
        }
      });
    };
  }
]);
