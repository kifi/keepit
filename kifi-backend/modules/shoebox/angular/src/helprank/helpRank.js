'use strict';

angular.module('kifi')

.controller('HelpRankCtrl', [
  '$scope', 'keepActionService', 'keepDecoratorService', '$stateParams', '$rootScope',
  function ($scope, keepActionService, keepDecoratorService, $stateParams, $rootScope) {
    //
    // Internal data.
    //
    var helprank = $stateParams.helprank || '';
    var selectedCount = 0;


    //
    // Scope data.
    //
    $scope.keeps = [];
    $scope.hasMore = true;
    $scope.scrollDistance = '100%';
    $scope.loading = false;


    //
    // Scope methods.
    //
    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }

      $scope.loading = true;

      var lastKeepId = $scope.keeps.length ? _.last($scope.keeps).id : null;
      return keepActionService.getKeepsByHelpRank(helprank, lastKeepId).then(function (result) {
        var rawKeeps = result.keeps;

        rawKeeps.forEach(function (rawKeep) {
          var keep = new keepDecoratorService.Keep(rawKeep);
          keep.buildKeep(keep);
          keep.makeKept();

          $scope.keeps.push(keep);
        });

        $scope.hasMore = !!result.mayHaveMore;
        $scope.loading = false;

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
          return 'No Keeps';
        case 1:
          return 'Showing the only Keep';
        case 2:
          return 'Showing both Keeps';
        default:
          if (!$scope.hasMore) {
            return 'Showing all ' + numShown + ' Keeps';
          }
          return 'Showing your ' + numShown + ' latest Keeps';
      }
    };

    $scope.updateSelectedCount = function (numSelected) {
      selectedCount = numSelected;
    };


    //
    // On HelpRankCtrl initialization.
    //
    $scope.getNextKeeps();
    $rootScope.$emit('libraryUrl', {});
  }
]);
