'use strict';

angular.module('kifi')

.controller('HomeCtrl', [
  '$scope', 'tagService', 'keepDecoratorService', 'keepActionService', '$q', '$timeout', '$window', 'installService', '$rootScope', 'modalService',
  function ($scope, tagService, keepDecoratorService, keepActionService, $q, $timeout, $window, installService, $rootScope, modalService) {
    //
    // Internal data.
    //
    var selectedCount = 0;


    //
    // Scope data.
    //
    $scope.keeps = [];
    $scope.hasLoaded = false;
    $scope.hasMore = true;
    $scope.loading = false;


    //
    // Scope methods.
    //
    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return $q.when([]);
      }

      $scope.loading = true;

      var lastKeepId = $scope.keeps.length ? _.last($scope.keeps).id : null;
      return keepActionService.getKeeps(lastKeepId).then(function (result) {
        var rawKeeps = result.keeps;

        rawKeeps.forEach(function (rawKeep) {
          var keep = new keepDecoratorService.Keep(rawKeep);
          keep.buildKeep(keep);
          keep.makeKept();

          $scope.keeps.push(keep);
        });

        $scope.hasMore = !!result.mayHaveMore;
        $scope.loading = false;
        $scope.hasLoaded = true;

        return $scope.keeps;
      });
    };

    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Loading...';
      }

      // If there are selected keeps, display the number of keeps
      // in the subtitle.
      if (selectedCount > 0) {
        return (selectedCount === 1) ? '1 Keep selected' : selectedCount + ' Keeps selected';
      }

      var numShown = $scope.keeps.length;
      switch (numShown) {
        case 0:
          return 'You have no keeps';
        case 1:
          return 'Showing your only keep';
        case 2:
          return 'Showing both of your keeps';
        default:
          if (!$scope.hasMore) {
            return 'Showing all ' + numShown + ' of your keeps';
          }
          return 'Showing your ' + numShown + ' latest keeps';
      }
    };

    $scope.updateSelectedCount = function (numSelected) {
      selectedCount = numSelected;
    };

    $scope.showEmptyState = function () {
      return tagService.getTotalKeepCount() === 0;
    };

    $scope.triggerInstall = function () {
      installService.triggerInstall(function () {
        $rootScope.$emit('showGlobalModal', 'installExtensionError');
      });
    };

    $scope.importBookmarks = function () {
      var kifiVersion = $window.document.documentElement.getAttribute('data-kifi-ext');

      if (!kifiVersion) {
        $rootScope.$emit('showGlobalModal','installExtension');
        return;
      }

      $rootScope.$emit('showGlobalModal', 'importBookmarks');
    };

    $scope.importBookmarkFile = function () {
      $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
    };

    $scope.addKeeps = function () {
      modalService.open({
        template: 'keeps/addKeepsModal.tpl.html'
      });
    };


    //
    // Watches and listeners.
    //
    $rootScope.$on('keepAdded', function (e, libSlug, keep) {
      $scope.keeps.unshift(keep);
    });


    //
    // On HomeCtrl initialization.
    //
    $window.document.title = 'Kifi • Your Keeps';
    $scope.enableSearch();
    $scope.getNextKeeps();
  }
]);
