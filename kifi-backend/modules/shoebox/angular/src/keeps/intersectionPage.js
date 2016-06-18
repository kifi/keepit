'use strict';

angular.module('kifi')

.controller('IntersectionPageCtrl', [ '$scope', '$rootScope', '$state', '$q', 'keepService', 'Paginator', 'util',
  function(scope, $rootScope, $state, $q, keepService, Paginator, util) {

    var paginationContext = null;
    var pageInfo = null;
    var init = false;

    scope.url = $state.params.url;
    if (!scope.url) {
      $rootScope.$emit('errorImmediately');
    } else {
      // populate cache
      keepService.getPageInfo(scope.url).then(function (result) {
        pageInfo = result.page;
      });
    }
    scope.keepsOnThisPage = null;
    scope.displayUrl = util.formatTitleFromUrl(scope.url);
    scope.entityType = $state.params.user ? 'user' : $state.params.library ? 'library' : $state.params.email ? 'email' : null;

    var intersectors =
      $state.params.user ? { users: [$state.params.user] } :
      $state.params.library ? { libraries: [$state.params.library] } :
      $state.params.email ? { emails: [$state.params.email] } : null;

    function keepSource() {
      return keepService.getKeepsAtIntersection(scope.url, intersectors, paginationContext).then(function (intResult) {
        paginationContext = intResult.paginationContext;
        scope.intersector = intResult.intersector.intersector || intResult.intersector;


        if (!init && intResult.keeps.length === 1) {
          var keep = intResult.keeps[0];
          $state.go('keepPage', { title: keep.title.slice(0,5), pubId: keep.id });
        } else {
          if (!init) {
            keepService.contextForPage(scope.url, paginationContext).then(function(contextResult) {
              scope.keepsOnThisPage = contextResult.keeps;
            });
          }

          var pageInfoP = pageInfo ? $q.when(pageInfo) : keepService.getPageInfo(scope.url).then(function(pageResult) {
            pageInfo = pageResult.page;
            return pageResult.page;
          });

          return pageInfoP.then(function(page) {
            return intResult.keeps.map(function(keep) {
              return util.retrofitKeepFormat(keep, page);
            });
          });
        }
      });
    }

    var keepPaginator = new Paginator(keepSource, 5, Paginator.DONE_WHEN_RESPONSE_IS_EMPTY);

    scope.fetchKeeps = function() {
      if (keepPaginator.hasMore()) {
        scope.loading = true;
        keepPaginator.fetch().then(function(keeps) {
          init = true;
          scope.loading = false;
          scope.intersectionKeeps = keeps;
        });
      }
    };

    scope.fetchKeeps();
  }
]);
