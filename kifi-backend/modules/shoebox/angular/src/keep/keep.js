'use strict';

angular.module('kifi.keep', ['kifi.keepWhoPics', 'kifi.keepWhoText', 'kifi.tagService'])

.controller('KeepCtrl', [
  '$scope',
  function ($scope) {
    $scope.isMyBookmark = function (keep) {
      return keep.isMyBookmark || false;
    };

    $scope.isPrivate = function (keep) {
      return keep.isPrivate || false;
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
  '$document', '$rootElement', 'tagService', 'util',
  function ($document, $rootElement, tagService, util) {
    return {
      restrict: 'A',
      scope: true,
      controller: 'KeepCtrl',
      templateUrl: 'keep/keep.tpl.html',
      link: function (scope, element /*, attrs*/ ) {
        scope.getTags = function () {
          return scope.keep.tagList;
        };

        var aUrlParser = $document[0].createElement('a');
        var secLevDomainRe = /[^.\/]+(?:\.[^.\/]{1,3})?\.[^.\/]+$/;
        var fileNameRe = /[^\/]+?(?=(?:\.[a-zA-Z0-9]{1,6}|\/|)$)/;
        var fileNameToSpaceRe = /[\/._-]/g;

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

        function updateTitleHtml() {
          scope.keep.titleHtml = toTitleHtml(scope.keep);
        }

        function updateDescHtml() {
          scope.keep.descHtml = formatDesc(scope.keep.url);
        }

        updateTitleHtml();
        updateDescHtml();

        var $k = element.find('li.kf-keep');

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
            'detailed': scope.isPreviewed(scope.keep),
            'selected': !!scope.isSelected(scope.keep)
          };
        }, function (cur) {
          _.forOwn(cur, function (value, key) {
            if (value && !$k.hasClass(key)) {
              $k.addClass(key);
            } else if (!value && $k.hasClass(key)) {
              $k.removeClass(key);
            }
          });
        });

        scope.$watch('keep.title', function () {
          updateTitleHtml();
        });

        scope.$watch('keep.url', function () {
          updateTitleHtml();
          updateDescHtml();
        });

        scope.getTitle = function () {
          var keep = scope.keep;
          return keep.title || keep.url;
        };

        scope.getName = function (user) {
          return [user.firstName, user.firstName].filter(function (n) {
            return !!n;
          }).join(' ');
        };

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
          return scope.toggleSelect(scope.keep);
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
          element.addClass('kf-dragged');
          var draggedKeepsElement = scope.dragKeeps(scope.keep);
          var sendData = angular.toJson(scope.draggedKeeps);
          e.dataTransfer.setData("Text", sendData);
          e.dataTransfer.setDragImage(draggedKeepsElement[0], mouseX, mouseY);
          scope.$apply(function () { scope.isDragging = true; });
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

.directive('kfDraggedKeep', [
  function () {
    return {
      restrict: 'A',
      scope: true,
      link: function (scope, element /*, attrs*/) {
        element.css({top: scope.$index * 37 + 'px', width: element.parent().parent()[0].offsetWidth + 'px'});
      }
    };
  }
]);
