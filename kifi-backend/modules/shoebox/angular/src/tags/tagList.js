'use strict';

angular.module('kifi')

.directive('kfTagList', [
  '$document', '$sce', '$log', 'keyIndices', 'hashtagService', '$location', '$state', '$analytics', '$rootScope',
  function ($document, $sce, $log, keyIndices, hashtagService, $location, $state, $analytics, $rootScope) {
    var dropdownSuggestionCount = 5;

    return {
      scope: {
        'getSelectedKeeps': '&',
        'addingTag': '=',
        'isShown': '&',
        'readOnlyMode': '&',
        'library': '=',
        'userLoggedIn': '='
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'tags/tagList.tpl.html',
      link: function (scope, element) {
        scope.data = {};
        scope.data.isClickingInList = false;
        scope.newTagLabel = 'NEW';
        scope.tagFilter = { name: '' };
        scope.tagTypeAheadResults = [];
        scope.shouldGiveFocus = false;

        scope.tagQuery = function (tag) {
          return 'tag:' + (tag.indexOf(' ') >= 0 ? '"' + tag + '"' : tag);
        };

        scope.$watch('getSelectedKeeps', function () {
          scope.tagFilter.name = '';
          filterTags(null);
          scope.hideAddTagDropdown();
        });

        function decorateTag(readOnly) {
          return function (name) {
            return { 'name': name, 'readOnly': readOnly };
          };
        }

        scope.$watch('addingTag.enabled', function () {
          if (scope.addingTag && scope.addingTag.enabled) {
            scope.shouldGiveFocus = true;
          }
        });

        function getCommonTags() {
          var tagLists = _.compact(_.pluck(scope.getSelectedKeeps(), 'hashtags'));
          return _.uniq(_.flatten(tagLists, true));
        }

        scope.$watchCollection(function () {
          if (scope.readOnlyMode()) {
            return _.flatten(_.pluck(scope.getSelectedKeeps(), 'tags'));
          } else {
            return _.flatten(_.pluck(scope.getSelectedKeeps(), 'hashtags'));
          }
        }, function () {
          scope.tagsToShow = _.map(getCommonTags(), decorateTag(scope.readOnlyMode()));
        });

        function indexOfTag(tag) {
          if (tag) {
            return scope.tagTypeAheadResults.indexOf(tag);
          }
          return -1;
        }

        function decorateTagSuggestion(tagMatch) {
          var safe = tagMatch.tag.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
          return {
            // todo: highlight matching terms
            'prettyHtml': $sce.trustAsHtml(safe),
            'name': tagMatch.tag,
            'normalizedName': tagMatch.tag.toLowerCase()
          };
        }

        var filterTags = _.debounce(function (tagFilterTerm) {
          if (tagFilterTerm === null) {
            return;
          }

          var isFirst = true;
          _.each(scope.getSelectedKeeps(), function (keep) {
            // todo(josh) debounce calls to the service
            hashtagService.suggestTags(keep, tagFilterTerm).then(function (data) {
              // ugly way to reset the typeAhead results when we resolve get suggestions for the first keep
              // we don't only want to reset it once; additional keeps should add unique tags to the typeahead
              if (isFirst) {
                scope.tagTypeAheadResults = [];
                isFirst = false;
              }
              var typeAheadResults = _.uniq(
                _.union(_.map(data, decorateTagSuggestion), scope.tagTypeAheadResults || []),
                function (tag) { return tag.normalizedName; });

              // remove tags that exist on the keep
              typeAheadResults = _.filter(typeAheadResults, function (tag) {
                return !_.contains(keep.hashtags, tag.name);
              });

              scope.tagTypeAheadResults = _.take(typeAheadResults, dropdownSuggestionCount);
              if (scope.tagTypeAheadResults.length > 0) {
                scope.highlightFirst();
              } else {
                scope.highlightTag(null);
              }
            });
          });
        }, 250);

        scope.addTag = function (tag) {
          tag = tag || scope.tagFilter.name;

          $analytics.eventTrack('user_clicked_page', {
            'action': 'addTagToKeeps',
            'path': $location.path()
          });

          var keeps = scope.getSelectedKeeps();
          hashtagService.tagKeeps(keeps, tag, true);
          scope.tagFilter.name = '';

          return scope.hideAddTagDropdown();
        };

        scope.isTagHighlighted = function (tag) {
          return scope.highlightedTag === tag;
        };

        // check if the highlighted tag is still in the list
        scope.checkHighlight = function () {
          if (!_.find(scope.tagTypeAheadResults, function (tag) { return scope.highlightedTag === tag; })) {
            scope.highlightTag(null);
          }
        };

        scope.$watch('tagFilter.name', function (tagFilterTerm) {
          filterTags(tagFilterTerm);
          scope.checkHighlight();
        });

        scope.highlightTag = function (tag) {
          return scope.highlightedTag = tag;
        };

        scope.highlightNext = function () {
          var index = indexOfTag(scope.highlightedTag);
          if (index === -1) {
            // no highlight
            // highlight the first
            return scope.highlightFirst();
          }
          if (index === scope.tagTypeAheadResults.length - 1) {
            // last item on the list
            if (scope.isAddTagShown()) {
              // highlight the new tag if available
              return scope.highlightNewSuggestion();
            }
            // the first, otherwise
            return scope.highlightFirst();
          }
          // highlight the next item
          return scope.highlightAt(index + 1);
        };

        scope.highlightPrev = function () {
          var index = indexOfTag(scope.highlightedTag);
          if (index === -1) {
            // no highlight
            // highlight the last
            return scope.highlightLast();
          }
          if (index === 0) {
            // first item on the list
            if (scope.isAddTagShown()) {
              // highlight the new tag if available
              return scope.highlightNewSuggestion();
            }
            // the last, otherwise
            return scope.highlightLast();
          }
          // highlight the previous item
          return scope.highlightAt(index - 1);
        };

        scope.isAddTagShown = function () {
          return !_.isEmpty(scope.tagFilter.name) && _.find(scope.tagTypeAheadResults, function (tag) {
            return tag.name.toLowerCase() === scope.tagFilter.name.toLowerCase();
          }) === undefined;
        };

        scope.highlightAt = function (index) {
          if (index == null) {
            return scope.highlightNewSuggestion();
          }
          var tags = scope.tagTypeAheadResults,
            len = tags.length;
          if (!len) {
            return scope.highlightNewSuggestion();
          }

          index = ((index % len) + len) % len;

          var tag = tags[index];
          scope.highlightTag(tag);
          return tag;
        };

        scope.highlightFirst = function () {
          scope.highlightAt(0);
        };

        scope.highlightLast = function () {
          return scope.highlightAt(-1);
        };

        scope.highlightNewSuggestion = function () {
          if (scope.isAddTagShown()) {
            return scope.highlightedTag = null;
          }
          return scope.highlightFirst();
        };

        scope.selectTag = function () {
          var tag = _.isObject(scope.highlightedTag) && scope.highlightedTag.name || scope.tagFilter.name;
          return scope.addTag(tag);
        };

        scope.hasTags = function () {
          return scope.tagsToShow && scope.tagsToShow.length > 0;
        };

        scope.showAddTagDropdown = function () {
          scope.tagFilter.name = '';
          filterTags('');
          scope.shouldGiveFocus = true;
          return scope.addingTag.enabled = true;
        };

        scope.hideAddTagDropdown = function () {
          // TODO: figure out why this check is necessary.
          // It seems like Angular processes this before addingTag in the parent
          // element (kf-keep) has a chance to be initialized.
          if (scope.addingTag) {
            return scope.addingTag.enabled = false;
          }
        };

        // We should be able to just replace the ng-show on the directive with an ng-if and remove the code
        // below. For some reason it is not working...
        scope.$watch('isShown()', function () {
          filterTags(null);
        });

        scope.onKeydown = function (e) {
          switch (e.keyCode) {
          case keyIndices.KEY_UP:
            scope.highlightPrev();
            break;
          case keyIndices.KEY_DOWN:
            scope.highlightNext();
            break;
          case keyIndices.KEY_ENTER:
            scope.selectTag();
            break;
          case keyIndices.KEY_ESC:
            scope.hideAddTagDropdown();
            break;
          case keyIndices.KEY_DEL:
            scope.hideAddTagDropdown();
            break;
          case keyIndices.KEY_F2:
            // scope.rename(scope.highlight);
            break;
          }
        };

        scope.removeTag = function (tag) {
          var keepsWithTag = scope.getSelectedKeeps().filter(function (keep) {
            return _.contains(keep.hashtags, tag);
          });

          $analytics.eventTrack('user_clicked_page', {
            'action': 'removeTagToKeeps',
            'path': $location.path()
          });

          _.each(keepsWithTag, function (keep) {
            hashtagService.untagKeep(keep, tag).then(function () {
              keep.removeTag(tag);
            });
          });
        };

        element.on('mousedown', '.kf-keep-tag-opt', function () {
          scope.data.isClickingInList = true;
        }).on('mouseup', '.kf-keep-tag-opt', function () {
          scope.data.isClickingInList = false;
        });

        scope.blurTagFilter = function () {
          if (!scope.data.isClickingInList) {
            scope.hideAddTagDropdown();
          }
        };

        scope.addTagLabel = function () {
          if (scope.getSelectedKeeps().length === 1) {
            return 'Add a tag to this keep';
          } else {
            return 'Add a tag to these keeps';
          }
        };

        scope.onTagClick = function (event) {
          if ($state.current.controller === 'SearchCtrl') {
            $location.url(event.target.getAttribute('href'));
          }
          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedTag' });
        };

        scope.highlightTag(null);
      }
    };
  }
])

.directive('kfTagSuggestions', [
  '$timeout',
  function ($timeout) {
    return function (scope, element) {
      var input = element.find('input');
      var options = element.find('.kf-keep-tag-opts');

      function adjustWidth() {
        var optionsWidth = options.outerWidth();
        input.outerWidth(optionsWidth);
      }

      adjustWidth();

      scope.$watch('tagFilter.name', function () {
        $timeout(adjustWidth);
      });
    };
  }
]);
