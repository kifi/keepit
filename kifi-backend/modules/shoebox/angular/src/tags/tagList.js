'use strict';

angular.module('kifi')

.directive('kfTagList', [
  '$document', '$sce', '$log', 'keyIndices', 'hashtagService',
  function ($document, $sce, $log, keyIndices, hashtagService) {
    var dropdownSuggestionCount = 5;

    return {
      scope: {
        'getSelectedKeeps': '&',
        'addingTag': '=',
        'isShown': '&',
        'readOnlyMode': '&'
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

        scope.searchTagUrl = function (tag) {
          return '/find?q=tag:' + encodeURIComponent(tag);
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
          if (scope.readOnlyMode()) {
            var keeps = scope.getSelectedKeeps() || [];
            if (keeps.length === 1 && keeps[0].tags && _.all(keeps[0].tags, _.isString)) {
              // since kthe keep objects come from different places (search, recos, libraries)
              // the above condition is to ensure that all tags are strings and not objects (which was
              // the case for libraries)
              scope.tagsToShow = _.map(keeps[0].tags, decorateTag(true));
            } else {
              scope.tagsToShow = [];
            }
          } else {
            scope.tagsToShow = _.map(getCommonTags(), decorateTag(false));
          }
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
          if (null === tagFilterTerm) {
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
          _.each(scope.getSelectedKeeps(), function (keep) {
            if (!keep.hasHashtag(tag)) {
              // add the tag immediately to provide faster feedback to the user
              keep.addTag(tag);
              hashtagService.tagKeep(keep, tag).then(function () {
                // all good
              }, function () {
                // something failed, get that tag out of here
                keep.removeTag(tag);
              });
            }
          });

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
          filterTags(null);
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

        scope.highlightTag(null);
      }
    };
  }
])

.directive('kfTagListDeprecated', [
  '$document', '$sce', 'keyIndices', 'tagService',
  function ($document, $sce, keyIndices, tagService) {
    var dropdownSuggestionCount = 5;

    return {
      scope: {
        'getSelectedKeeps': '&',
        'addingTag': '=',
        'isShown': '&'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'tags/tagListDeprecated.tpl.html',
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
          var tagLists = _.compact(_.pluck(scope.getSelectedKeeps(), 'tagList'));
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
            var tagIds = _.pluck(keep.tagList, 'id');
            return _.contains(tagIds, tag.id);
          });
          tagService.removeKeepsFromTag(tag.id, keepsWithTag).then(function () {
            //filterTags(null);
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
