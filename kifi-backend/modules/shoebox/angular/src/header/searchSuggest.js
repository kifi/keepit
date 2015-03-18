'use strict';

angular.module('kifi')

.directive('kfSearchSuggest', [
  '$document', '$location', '$timeout', 'searchSuggestService', 'libraryService', 'profileService', 'keyIndices',
  function ($document, $location, $timeout, searchSuggestService, libraryService, profileService, keyIndices) {
    return {
      restrict: 'A',
      scope: {
        search: '=kfSearchSuggest',
        libraryId: '='
      },
      templateUrl: 'header/searchSuggest.tpl.html',
      link: function (scope, element) {
        var libInfoComparePropsDesc = ['lastViewed', 'lastKept', 'numKeeps', 'numFollowers'];
        var inEmptyQueryState;

        scope.me = profileService.me;
        scope.working = false;
        scope.inLibrary = false;
        scope.uris = null;
        scope.libraries = null;
        scope.users = null;

        suggest();

        //
        // Watches
        //

        scope.$watch('search.text', onSearchCriterionChange);

        if (scope.libraryId) {
          scope.$watch('search.libraryChip', onSearchCriterionChange);
        }

        //
        // DOM Events
        //

        // TODO: figure out how to reliably active menu links from mouse and keyboard and hide & clear search when done

        element.on('mousemove', onMouseMove);
        function onMouseMove(e) {
          element.off('mousemove', onMouseMove);
          var itemEl = angular.element(e.target).closest('.kf-ssg-a:not(.kf-selected)');
          if (itemEl.length) {
            itemEl.siblings('.kf-ssg-a.kf-selected').removeClass('kf-selected');
            itemEl.addClass('kf-selected');
          }
        }

        element.on('mouseenter', '.kf-ssg-a', function (e) {
          var itemEl = angular.element(e.currentTarget);
          if (!itemEl.hasClass('kf-selected')) {
            itemEl.siblings('.kf-ssg-a.kf-selected').removeClass('kf-selected');
            itemEl.addClass('kf-selected');
          }
        });

        element.on('mousedown', function (e) {
          e.preventDefault(); // prevents search input blur
          var itemEl = angular.element(e.target).closest('.kf-ssg-a');
          if (itemEl.length) {
            onSuggestionTaken(itemEl);
          }
        });

        $document.on('keydown', onDocKeyDown);
        scope.$on('$destroy', function () {
          $document.off('keydown', onDocKeyDown);
        });
        function onDocKeyDown(e) {
          if (!e.isDefaultPrevented()) {
            var itemEl;
            switch (e.which) {
              case keyIndices.KEY_ENTER:
                itemEl = element.find('.kf-ssg-a.kf-selected');
                if (itemEl.length) {
                  e.preventDefault();
                  onSuggestionTaken(itemEl);
                }
                break;
              case keyIndices.KEY_UP:
              case keyIndices.KEY_DOWN:
                e.preventDefault();
                var up = e.which === keyIndices.KEY_UP;
                itemEl = element.find('.kf-ssg-a.kf-selected').removeClass('kf-selected');
                itemEl = itemEl[up ? 'prevAll' : 'nextAll']('.kf-ssg-a').first();
                (itemEl.length ? itemEl : element.find('.kf-ssg-a')[up ? 'last' : 'first']()).addClass('kf-selected');
                element.on('mousemove', onMouseMove);
                break;
            }
          }
        }

        //
        // Helper functions
        //

        function onSearchCriterionChange(newVal, oldVal) {
          if (scope.search.suggesting && newVal !== oldVal) {
            suggest();
          }
        }

        function suggest() {
          var q = scope.search.text.trimLeft();
          if (q) {
            var libraryId = scope.search.libraryChip ? scope.libraryId : null;
            if (inEmptyQueryState) {
              inEmptyQueryState = false;
              scope.libraries = null;
            }
            scope.working = true;
            searchSuggestService.suggest(q, libraryId).then(function (data) {
              if (scope.search.text.trimLeft() === q && (scope.search.libraryChip ? scope.libraryId : null) === libraryId) {
                scope.inLibrary = !!libraryId;
                scope.users = data.users && data.users.hits;
                scope.libraries = data.libraries && data.libraries.hits;
                scope.uris = data.uris && data.uris.hits;
                scope.$evalAsync(function () {
                  if (!element.find('.kf-selected').length) {
                    element.find('.kf-ssg-query').addClass('kf-selected');
                  }
                });
              }
            })['finally'](function () {
              if (scope.search.text.trimLeft() === q) {
                scope.working = false;
              }
            });
          } else {
            inEmptyQueryState = true;
            scope.working = false;
            scope.inLibrary = false;
            scope.uris = null;
            scope.libraries = libraryService.getOwnInfos().filter(notAtLibInfo.bind(null, $location.url())).sort(compareLibInfos).slice(0, 7).map(adaptLibInfo);
            scope.users = null;
          }
        }

        function notAtLibInfo(url, info) {
          return info.url !== url;
        }

        function compareLibInfos(a, b) {
          for (var i = 0; i < libInfoComparePropsDesc.length; i++) {
            var prop = libInfoComparePropsDesc[i], ap = a[prop], bp = b[prop];
            if (ap) {
              return (bp || 0) - ap;
            }
            if (bp) {
              return 1;
            }
          }
          return a.name < b.name ? -1 : a.name === b.name ? 0 : 1;
        }

        function adaptLibInfo(info) {
          return _.assign({
            path: info.url,
            followerCount: info.numFollowers,
            keepCount: info.numKeeps
          }, info);
        }

        function onSuggestionTaken(el) {
          var href = el.attr('href');
          if ($location.url() !== href) {
            if (el.prop('target') === '_blank') {
              el[0].click();
              scope.search.text = '';
            } else {
              scope.$apply(function () {
                $location.url(href);
              });
            }
          }
          angular.element(document.activeElement).filter('.kf-lih-search-input').blur();
        }
      }
    };
  }
])

.factory('searchSuggestService', [
  'Clutch', '$http', 'routeService',
  function (Clutch, $http, routeService) {
    function getData(res) {
      return res.data;
    }

    var clutch = new Clutch(function (q, libraryId) {
      var params = {q: q, l: libraryId || [], maxUsers: 3, maxLibraries: 3, maxUris: 3};
      return $http.get(routeService.searchSuggest(params)).then(getData);
    }, {cacheDuration: 15000});

    return {
      suggest: clutch.get.bind(clutch)
    };
  }
]);
