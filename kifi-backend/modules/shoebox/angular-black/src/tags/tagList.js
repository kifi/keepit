'use strict';

angular.module('kifi.tagList', ['kifi.keepService', 'kifi.tagService'])

.directive('kfTagList', [
  'keepService', 'tagService', '$filter', '$sce', '$document',
  function (keepService, tagService, $filter, $sce, $document) {
    var KEY_UP = 38,
      KEY_DOWN = 40,
      KEY_ENTER = 13,
      KEY_ESC = 27,
      KEY_DEL = 46,
      KEY_F2 = 113;
    var dropdownSuggestionCount = 5;

    return {
      scope: {
        'getSelectedKeeps': '&',
        'addingTag': '='
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'tags/tagList.tpl.html',
      link: function (scope, element/*, attrs*/ ) {
        scope.data = {};
        scope.data.isClickingInList = false;
        scope.newTagLabel = 'NEW';
        scope.tagFilter = { name: '' };
        scope.tagTypeAheadResults = [];
        scope.shouldGiveFocus = false;

        tagService.fetchAll().then(function () {
          scope.allTags = tagService.allTags;
          filterTags(null);
        });

        scope.$watch('getSelectedKeeps', function () {
          scope.tagFilter.name = '';
          filterTags(null);
          scope.hideAddTagDropdown();
        });

        scope.$watch('addingTag.enabled', function () {
          if (scope.addingTag && scope.addingTag.enabled) {
            scope.shouldGiveFocus = true;
          }
        });

        scope.getCommonTags = function () {
          var tagLists = _.pluck(scope.getSelectedKeeps(), 'tagList');
          var tagIds = _.map(tagLists, function (tagList) { return _.pluck(tagList, 'id'); });
          var commonTagIds = _.union.apply(this, tagIds);
          var tagMap = _.indexBy(_.flatten(tagLists, true), 'id');
          return _.map(commonTagIds, function (tagId) { return tagMap[tagId]; });
        };

        scope.$watchCollection(function () {
          return _.flatten(_.pluck(scope.getSelectedKeeps(), 'tagList'));
        }, function () {
          scope.commonTags = scope.getCommonTags();
        });

        function indexOfTag(tag) {
          if (tag) {
            return scope.tagTypeAheadResults.indexOf(tag);
          }
          return -1;
        }

        function filterTags(tagFilterTerm) {
          function selectedKeepsHaveTag(tagId) {
            return scope.allTags && scope.commonTags && !!_.find(scope.commonTags, function (keepTag) {
              return keepTag.id === tagId;
            });
          }
          function allTagsExceptPreexisting() {
            return scope.allTags.filter(function (tag) {
              return !selectedKeepsHaveTag(tag.id);
            }).slice(0, dropdownSuggestionCount);
          }
          function generateDropdownSuggestionCount() {
            var elem = element.find('.kf-tag-list');
            if (elem && elem.offset().top) {
              return Math.min(10, Math.max(3, ($document.height() - elem.offset().top) / 24 - 1));
            }
            return dropdownSuggestionCount;
          }
          var splitTf = tagFilterTerm && tagFilterTerm.split(/[\W]+/);
          dropdownSuggestionCount = generateDropdownSuggestionCount();
          if (scope.allTags && tagFilterTerm) {
            var filtered = scope.allTags.filter(function (tag) {
              // for given tagFilterTerm (user search value) and a tag, returns true if
              // every part of the tagFilterTerm exists at the beginning of a part of the tag

              return !selectedKeepsHaveTag(tag.id) && splitTf.every(function (tfTerm) {
                return _.find(tag.name.split(/[\W]+/), function (tagTerm) {
                  return tagTerm.toLowerCase().indexOf(tfTerm.toLowerCase()) === 0;
                });
              });
            });
            scope.tagTypeAheadResults = filtered.slice(0, dropdownSuggestionCount);
          } else if (scope.allTags && !tagFilterTerm) {
            scope.tagTypeAheadResults = allTagsExceptPreexisting();
          }

          if (scope.tagTypeAheadResults.length > 0) {
            scope.highlightFirst();
          }

          scope.tagTypeAheadResults.forEach(function (tag) {
            var safe = tag.name.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            // todo: highlight matching terms
            tag.prettyHtml = $sce.trustAsHtml(safe);
          });
        }

        scope.addTag = function (tag) {
          tagService.addKeepsToTag(tag, scope.getSelectedKeeps());
          scope.tagFilter.name = '';
          return scope.hideAddTagDropdown();
        };

        scope.createAndAddTag = function () {
          tagService.create(scope.tagFilter.name).then(function (tag) {
            scope.addTag(tag);
          });
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
          return scope.tagFilter.name.length > 0 && _.find(scope.tagTypeAheadResults, function (tag) {
            return tag.name === scope.tagFilter.name;
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
          if (scope.highlightedTag) {
            return scope.addTag(scope.highlightedTag);
          }
          return scope.createAndAddTag();
        };

        scope.hasTags = function () {
          return scope.commonTags && scope.commonTags.length > 0;
        };

        scope.showAddTagDropdown = function () {
          scope.tagFilter.name = '';
          filterTags(null);
          scope.shouldGiveFocus = true;
          return scope.addingTag.enabled = true;
        };

        scope.hideAddTagDropdown = function () {
          return scope.addingTag.enabled = false;
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
            scope.selectTag();
            break;
          case KEY_ESC:
            scope.hideAddTagDropdown();
            break;
          case KEY_DEL:
            scope.hideAddTagDropdown();
            break;
          case KEY_F2:
            // scope.rename(scope.highlight);
            break;
          }
        };

        scope.removeTag = function (tag) {
          var keepsWithTag = scope.getSelectedKeeps().filter(function (keep) {
            var tagIds = _.pluck(keep.tagList, 'id');
            return _.contains(tagIds, tag.id);
          });
          tagService.removeKeepsFromTag(tag.id, keepsWithTag);
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
