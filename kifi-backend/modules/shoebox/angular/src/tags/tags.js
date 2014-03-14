'use strict';

angular.module('kifi.tags', ['util', 'dom', 'kifi.tagService', 'kifi.tagItem'])

.controller('TagsCtrl', [
  '$scope', '$timeout', 'tagService',
  function ($scope, $timeout, tagService) {
    $scope.create = function (name) {
      if (name) {
        return tagService.create(name)
          .then(function (tag) {
            tag.isNew = true;
            $scope.clearFilter();

            $timeout(function () {
              delete tag.isNew;
            }, 3000);

            return tag;
          });
      }
    };
  }
])

.directive('kfTags', [
  '$timeout', '$window', '$rootScope', '$location', 'util', 'dom', 'tagService', 'profileService',
  function ($timeout, $window, $rootScope, $location, util, dom, tagService, profileService) {
    var KEY_UP = 38,
      KEY_DOWN = 40,
      KEY_ENTER = 13,
      KEY_ESC = 27,
      //KEY_TAB = 9,
      KEY_DEL = 46,
      KEY_F2 = 113;

    return {
      restrict: 'A',
      templateUrl: 'tags/tags.tpl.html',
      scope: {},
      controller: 'TagsCtrl',
      link: function (scope, element /*, attrs*/ ) {
        scope.tags = tagService.list;

        scope.clearFilter = function (focus) {
          scope.filter.name = '';
          if (focus) {
            scope.focusFilter = true;
          }
        };

        scope.unfocus = function () {
          scope.lastHighlight = scope.highlight;
        };

        scope.refocus = function () {
          if (scope.lastHighlight && !scope.highlight) {
            scope.highlight = scope.lastHighlight;
          }
          scope.lastHighlight = null;
          scope.focusFilter = true;
        };

        function getFilterValue() {
          return scope.filter && scope.filter.name || '';
        }

        scope.showAddTag = function () {
          var name = getFilterValue(),
            res = false;
          if (name) {
            name = name.toLowerCase();
            res = !scope.tags.some(function (tag) {
              return tag.name.toLowerCase() === name;
            });
          }
          scope.isAddTagShown = res;
          return res;
        };

        scope.isActiveTag = function (tag) {
          return util.startsWith($location.path(), '/tag/' + tag.id);
        };

        scope.getShownTags = function () {
          var child = scope.$$childHead;
          while (child) {
            if (child.shownTags) {
              return child.shownTags;
            }
            child = child.$$nextSibling;
          }
          return scope.tags || [];
        };

        function indexOfTag(tag) {
          if (tag) {
            return scope.getShownTags().indexOf(tag);
          }
          return -1;
        }

        scope.viewTag = function (tag) {
          if (tag) {
            return $location.path('/tag/' + tag.id);
          }
        };

        scope.select = function () {
          if (scope.highlight) {
            return scope.viewTag(scope.highlight);
          }
          return scope.create(getFilterValue());
        };

        scope.onKeydown = function (e) {
          switch (e.keyCode) {
          case KEY_UP:
            scope.highlightPrev();
            break;
          case KEY_DOWN:
            scope.highlightNext();
            break;
          case KEY_ENTER:
            scope.select();
            break;
          case KEY_ESC:
            if (scope.highlight) {
              scope.dehighlight();
            }
            else {
              scope.clearFilter();
            }
            break;
          case KEY_DEL:
            scope.remove(scope.highlight);
            break;
          case KEY_F2:
            scope.rename(scope.highlight);
            break;
          }
        };

        scope.refreshHighlight = function () {
          var shownTags = scope.getShownTags();
          var highlight = scope.highlight;
          if (highlight) {
            var index = shownTags.indexOf(highlight);
            if (index !== -1) {
              // might scroll
              return scope.highlightAt(index);
            }
          }

          if (getFilterValue() && shownTags.length) {
            return scope.highlightFirst();
          }

          return scope.dehighlight();
        };

        scope.isHighlight = function (tag) {
          return scope.highlight === tag;
        };

        scope.isHighlightNew = function () {
          return !scope.highlight && !! getFilterValue();
        };

        scope.dehighlight = function () {
          scope.highlight = null;
          if (scope.isAddTagShown) {
            dom.scrollIntoViewLazy(element.find('.kf-tag-new')[0]);
          }
          return null;
        };

        scope.highlightAt = function (index) {
          if (index == null) {
            return scope.dehighlight();
          }

          var tags = scope.getShownTags(),
            len = tags.length;
          if (!len) {
            return scope.dehighlight();
          }

          index = ((index % len) + len) % len;
          var tag = tags[index];
          scope.highlight = tag;
          dom.scrollIntoViewLazy(element.find('.kf-tag')[index]);
          return tag;
        };

        scope.highlightFirst = function () {
          return scope.highlightAt(0);
        };

        scope.highlightLast = function () {
          return scope.highlightAt(-1);
        };

        scope.highlightNext = function () {
          if (scope.isHighlightNew()) {
            // new tag is highlighted
            // highlight the first
            return scope.highlightFirst();
          }

          var index = indexOfTag(scope.highlight);
          if (index === -1) {
            // no highlight
            // highlight the first
            return scope.highlightFirst();
          }

          if (index === scope.getShownTags().length - 1) {
            // last item on the list

            if (scope.isAddTagShown) {
              // highlight the new tag if available
              return scope.dehighlight();
            }

            // the first, otherwise
            return scope.highlightFirst();
          }

          // highlight the next item
          return scope.highlightAt(index + 1);
        };

        scope.highlightPrev = function () {
          if (scope.isHighlightNew()) {
            // new tag is highlighted
            // highlight the last
            return scope.highlightLast();
          }

          var index = indexOfTag(scope.highlight);
          if (index === -1) {
            // no highlight
            // highlight the last
            return scope.highlightLast();
          }

          if (index === 0) {
            // first item on the list

            if (scope.isAddTagShown) {
              // highlight the new tag if available
              return scope.dehighlight();
            }

            // the last, otherwise
            return scope.highlightLast();
          }

          // highlight the prev item
          return scope.highlightAt(index - 1);
        };

        var list = element.find('.kf-tag-list');
        var hidden = element.find('.kf-tag-list-hidden');

        function positionTagsList() {
          list.css({
            position: 'absolute',
            top: hidden.position().top,
            bottom: 0
          });
        }
        $timeout(positionTagsList);

        scope.$watch(function () {
          return profileService.me.seqNum;
        }, positionTagsList);

        angular.element($window).resize(_.throttle(function () {
          positionTagsList();
          scope.refreshScroll();
        }, 150));

        scope.$watch('filter.name', function () {
          $timeout(scope.refreshHighlight);
          scope.refreshScroll();
        });

        scope.$watch('tags.length', function () {
          scope.refreshScroll();
        });

        tagService.fetchAll();
      }
    };
  }
]);
