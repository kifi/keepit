'use strict';

angular.module('kifi')

.directive('kfKeepCardAttribution', [ '$state', 'profileService',
  function ($state, profileService) {
    return {
      scope: {
        keep: '=keep',
        showLibraryAttribution: '=',
        isFirstItem: '='
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'keep/keepCardAttribution.tpl.html',
      link: function (scope) {
        var keep = scope.keep;
        var discussion = keep && keep.discussion;
        scope.showKeepPageLink = scope.keep.path && !$state.is('keepPage');
        scope.showAsDiscussion = scope.keep && !scope.keep.libraryId && profileService.hasExperiment('keep_nolib');
        scope.attributionTime = (scope.showAsDiscussion && discussion && discussion.startedAt) || (keep && keep.createdAt);
      }
    };
  }
]);
