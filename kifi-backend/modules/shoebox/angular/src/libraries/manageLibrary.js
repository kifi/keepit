'use strict';

angular.module('kifi')

.controller('ManageLibraryCtrl', [
  '$scope', '$routeParams', 'libraryService', 'util', '$timeout', '$location',
  function ($scope, $routeParams, libraryService, util, $timeout, $location) {
    $scope.$error = {};
    $scope.userHasEditedSlug = false;
    var returnAction;

    if (libraryService.libraryState.library) {
      $scope.modifyingExistingLibrary = true;
      $scope.library = libraryService.libraryState.library;
      returnAction = libraryService.libraryState.returnAction || null;
    } else {
      $scope.library = {
        'name': '',
        'visibility': 'discoverable',
        'description': '',
        'slug': ''
      };
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


    $scope.createLibrary = function () {
      var newError = false;
      if ($scope.library.name.length < 3) {
        $scope.$error.name = ' Try a longer name';
        newError = true;
      }
      if (newError || $scope.submitting) {
        return;
      }
      $scope.submitting = true;
      libraryService.createLibrary($scope.library).then(function (resp) {
          $scope.$error = {};
          $scope.submitting = false;
          libraryService.fetchLibrarySummaries(true);
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
