'use strict';

angular.module('kifi.keep', ['kifi.keepWhoPics', 'kifi.keepWhoText', 'kifi.tagService'])

.controller('KeepCtrl', [
  '$scope',
  function ($scope) {
    $scope.isMyBookmark = function (keep) {
      return keep.isMyBookmark || false;
    };

    $scope.isExampleTag = function (tag) {
      return (tag && tag.name && tag.name.toLowerCase()) === 'example keep';
    };

    function hasExampleTag(tags) {
      if (tags && tags.length) {
        for (var i = 0, l = tags.length; i < l; i++) {
          if ($scope.isExampleTag(tags[i])) {
            return true;
          }
        }
      }
      return false;
    }

    $scope.isExample = function (keep) {
      if (keep.isExample == null) {
        keep.isExample = hasExampleTag($scope.getTags());
      }
      return keep.isExample;
    };
  }
])

.directive('kfKeep', [
  '$document', '$rootElement', '$timeout', 'tagService', 'keepService', 'util',
  function ($document, $rootElement, $timeout, tagService, keepService, util) {
    return {
      restrict: 'A',
      scope: {
        keep: '=',
        me: '=',
        toggleSelect: '&',
        isPreviewed: '&',
        isSelected: '&',
        clickAction: '&',
        dragKeeps: '&',
        stopDraggingKeeps: '&'
      },
      controller: 'KeepCtrl',
      replace: true,
      templateUrl: 'keep/keep.tpl.html',
      link: function (scope, element /*, attrs*/ ) {

        var aUrlParser = $document[0].createElement('a');
        var secLevDomainRe = /[^.\/]+(?:\.[^.\/]{1,3})?\.[^.\/]+$/;
        var fileNameRe = /[^\/]+?(?=(?:\.[a-zA-Z0-9]{1,6}|\/|)$)/;
        var fileNameToSpaceRe = /[\/._-]/g;
        var imageWidthThreshold = 300;

        scope.getTags = function () {
          return scope.keep.tagList;
        };

        scope.hasTag = function () {
          return !!scope.getTags().length;
        };

        scope.unkeep = function () {
          keepService.unkeep([scope.keep]);
        };

        scope.isPrivate = function () {
          return scope.keep.isPrivate || false;
        };

        scope.togglePrivate = function () {
          keepService.togglePrivate([scope.keep]);
        };

        function formatTitleFromUrl(url, matches) {
          aUrlParser.href = url;

          var domain = aUrlParser.hostname;
          var domainIdx = url.indexOf(domain);
          var domainMatch = domain.match(secLevDomainRe);
          if (domainMatch) {
            domainIdx += domainMatch.index;
            domain = domainMatch[0];
          }

          var fileName = aUrlParser.pathname;
          var fileNameIdx = url.indexOf(fileName, domainIdx + domain.length);
          var fileNameMatch = fileName.match(fileNameRe);
          if (fileNameMatch) {
            fileNameIdx += fileNameMatch.index;
            fileName = fileNameMatch[0];
          }
          fileName = fileName.replace(fileNameToSpaceRe, ' ').trimRight();

          for (var i = matches && matches.length; i--;) {
            var match = matches[i];
            var start = match[0],
              len = match[1];
            if (start >= fileNameIdx && start < fileNameIdx + fileName.length) {
              fileName = bolded(fileName, start - fileNameIdx, len);
            }
            else if (start >= domainIdx && start < domainIdx + domain.length) {
              domain = bolded(domain, start - domainIdx, len);
            }
          }
          fileName = fileName.trimLeft();

          return domain + (fileName ? ' · ' + fileName : '');
        }

        function bolded(text, start, len) {
          return text.substr(0, start) + '<b>' + text.substr(start, len) + '</b>' + text.substr(start + len);
        }

        function toTitleHtml(keep) {
          return keep.title || formatTitleFromUrl(keep.url);
        }

        var strippedSchemeRe = /^https?:\/\//;
        var domainTrailingSlashRe = /^([^\/]*)\/$/;

        function formatDesc(url, matches) {
          var strippedSchemeLen = (url.match(strippedSchemeRe) || [''])[0].length;
          url = url.substr(strippedSchemeLen).replace(domainTrailingSlashRe, '$1');
          for (var i = matches && matches.length; i--;) {
            matches[i][0] -= strippedSchemeLen;
          }
          return boldSearchTerms(url, matches);
        }

        function boldSearchTerms(text, matches) {
          for (var i = matches && matches.length; i--;) {
            var match = matches[i];
            var start = match[0];
            if (start >= 0) {
              text = bolded(text, start, match[1]);
            }
          }
          return text;
        }

        function getSite() {
          var keep = scope.keep;
          return keep.siteName || keep.url;
        }

        function updateTitleHtml() {
          scope.keep.titleHtml = toTitleHtml(scope.keep);
        }

        function updateSiteDescHtml() {
          scope.keep.descHtml = formatDesc(getSite());
        }

        updateTitleHtml();
        updateSiteDescHtml();

        // Really weird hack to fix a ng-class bug
        // In certain cases, ng-class is not setting DOM classes correctly.
        // Reproduction: select several keeps, preview one of the keeps,
        // unselect it. isSelected(keep) is false, but it'll still appear
        // as checked.
        scope.$watchCollection(function () {
          return {
            'mine': scope.isMyBookmark(scope.keep),
            'example': scope.isExample(scope.keep),
            'private': scope.isPrivate(scope.keep),
            'detailed': scope.isPreviewed({keep: scope.keep}),
            'selected': !!scope.isSelected({keep: scope.keep})
          };
        }, function (cur) {
          _.forOwn(cur, function (value, key) {
            if (value && !element.hasClass(key)) {
              element.addClass(key);
            } else if (!value && element.hasClass(key)) {
              element.removeClass(key);
            }
          });
        });

        scope.$watch('keep.title', function () {
          updateTitleHtml();
        });

        scope.$watch('keep.url', function () {
          updateTitleHtml();
          updateSiteDescHtml();
        });

        scope.getTitle = function () {
          var keep = scope.keep;
          return keep.title || keep.url;
        };

        scope.getSite = getSite;

        scope.getName = function (user) {
          return [user.firstName, user.firstName].filter(function (n) {
            return !!n;
          }).join(' ');
        };

        scope.hasBigImage = function () {
          var keep = scope.keep;
          return keep.summary && keep.summary.imageWidth && keep.summary.imageWidth >= imageWidthThreshold;
        };

        scope.hasSmallImage = function () {
          var keep = scope.keep;
          return keep.summary && keep.summary.imageWidth && keep.summary.imageWidth < imageWidthThreshold;
        };

        $timeout(function () {
          var img = element.find('.kf-keep-small-image');
          if (img) {
            var imgWidth = scope.keep.summary.imageWidth;
            img.css({maxWidth: imgWidth});
          }
        });

        scope.hasKeepers = function () {
          var keep = scope.keep;
          return !!(keep.keepers && keep.keepers.length);
        };

        scope.showOthers = function () {
          return !scope.hasKeepers() && !! scope.keep.others;
        };

        scope.onCheck = function (e) {
          // needed to prevent previewing
          e.stopPropagation();
          return scope.toggleSelect();
        };

        scope.removeTag = function (tag) {
          tagService.removeKeepsFromTag(tag.id, scope.keep.id);
        };

        var dragMask = element.find('.kf-drag-mask');
        scope.isDragTarget = false;

        scope.onTagDrop = function (tag) {
          tagService.addKeepToTag(tag, scope.keep);
          scope.isDragTarget = false;
        };

        dragMask.on('dragenter', function () {
          scope.$apply(function () { scope.isDragTarget = true; });
        });

        dragMask.on('dragleave', function () {
          scope.$apply(function () { scope.isDragTarget = false; });
        });

        scope.isDragging = false;
        var mouseX, mouseY;
        element.on('mousemove', function (e) {
          mouseX = e.pageX - util.offset(element).left;
          mouseY = e.pageY - util.offset(element).top;
        });
        element.on('dragstart', function (e) {
          scope.$apply(function () {
            element.addClass('kf-dragged');
            scope.dragKeeps({keep: scope.keep, event: e, mouseX: mouseX, mouseY: mouseY});
            scope.isDragging = true;
          });
        });
        element.on('dragend', function () {
          scope.$apply(function () {
            element.removeClass('kf-dragged');
            scope.stopDraggingKeeps();
            scope.isDragging = false;
          });
        });
      }
    };
  }
])


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
        'keep': '=',
        'getSelectedKeeps': '&'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'keep/tagList.tpl.html',
      link: function (scope, element/*, attrs*/ ) {
        scope.data = {};
        scope.data.isClickingInList = false;
        scope.newTagLabel = 'NEW';
        scope.tagFilter = { name: '' };
        scope.tagTypeAheadResults = [];
        scope.shouldGiveFocus = false;

        tagService.fetchAll().then(function (res) {
          scope.allTags = res;
          filterTags(null);
        });

        scope.$watch('keep', function () {
          scope.tagFilter.name = '';
          filterTags(null);
          scope.hideAddTagDropdown();
        });

        scope.getCommonTags = function () {
          var tagLists = _.pluck(scope.getSelectedKeeps(), 'tagList');
          var tagIds = _.map(tagLists, function (tagList) { return _.pluck(tagList, 'id'); });
          var commonTagIds = _.union.apply(this, tagIds);
          var tagMap = _.indexBy(_.flatten(tagLists, true), 'id');
          return _.map(commonTagIds, function (tagId) { return tagMap[tagId]; });
        };

        scope.$watch(function () {
          return _.pluck(scope.getSelectedKeeps(), 'tagList');
        }, function () {
          scope.commonTags = scope.getCommonTags();
        }, true);

        function indexOfTag(tag) {
          if (tag) {
            return scope.tagTypeAheadResults.indexOf(tag);
          }
          return -1;
        }

        function filterTags(tagFilterTerm) {
          function keepHasTag(tagId) {
            return scope.keep && scope.allTags && scope.commonTags && !!_.find(scope.commonTags, function (keepTag) {
              return keepTag.id === tagId;
            });
          }
          function allTagsExceptPreexisting() {
            return scope.allTags.filter(function (tag) {
              return !keepHasTag(tag.id);
            }).slice(0, dropdownSuggestionCount);
          }
          function generateDropdownSuggestionCount() {
            var elem = element.find('.page-coll-list');
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

              return !keepHasTag(tag.id) && splitTf.every(function (tfTerm) {
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

        scope.createAndAddTag = function (keep) {
          tagService.create(scope.tagFilter.name).then(function (tag) {
            scope.addTag(tag, keep);
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
            return scope.addTag(scope.highlightedTag, scope.keep);
          }
          return scope.createAndAddTag(scope.keep);
        };

        scope.hasTags = function () {
          return scope.commonTags && scope.commonTags.length > 0;
        };

        scope.showAddTagDropdown = function () {
          scope.tagFilter.name = '';
          filterTags(null);
          scope.shouldGiveFocus = true;

          return scope.isAddingTag = true;
        };

        scope.hideAddTagDropdown = function () {
          return scope.isAddingTag = false;
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

        scope.removeTagFromSelectedKeeps = function (tag) {
          var keepsWithTag = scope.getSelectedKeeps().filter(function (keep) {
            var tagIds = _.pluck(keep.tagList, 'id');
            return _.contains(tagIds, tag.id);
          });
          tagService.removeKeepsFromTag(tag.id, _.pluck(keepsWithTag, 'id'));
        };

        element.on('mousedown', '.page-coll-opt', function () {
          scope.data.isClickingInList = true;
        }).on('mouseup', '.page-coll-opt', function () {
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
      $timeout(function () {
        var hiddenElement = element.find('.page-coll-opt-hidden');
        var input = element.find('input');
        scope.$watch('tagFilter.name', function (value) {
          var html = value;
          if (scope.isAddTagShown()) {
            html += scope.newTagLabel;
          }
          hiddenElement.html(html);
          var parentWidth = element.parents('.page-coll-list')[0].offsetWidth - 20; // a padding offset
          var width = hiddenElement[0].offsetWidth + 10;
          if (width > parentWidth) {
            width = parentWidth;
          }
          input.css('width', width + 'px');
        });
      });
    };
  }
]);
