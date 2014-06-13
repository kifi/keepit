'use strict';

angular.module('kifi.keep', ['kifi.keepWhoPics', 'kifi.keepWhoText', 'kifi.tagService'])

.controller('KeepCtrl', [
  '$scope',
  function ($scope) {
    $scope.isMyBookmark = function (keep) {
      return (keep && keep.isMyBookmark) || false;
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
      if (keep && keep.isExample == null) {
        keep.isExample = hasExampleTag($scope.getTags());
        return keep.isExample;
      }
    };
  }
])

.directive('kfKeep', [
  '$document', '$rootScope', '$rootElement', '$timeout', 'tagService', 'keepService', 'installService', 'util',
  function ($document, $rootScope, $rootElement, $timeout, tagService, keepService, installService, util) {
    return {
      restrict: 'A',
      scope: {
        keep: '=',
        me: '=',
        toggleSelect: '&',
        isSelected: '&',
        clickAction: '&',
        dragKeeps: '&',
        stopDraggingKeeps: '&',
        editMode: '='
      },
      controller: 'KeepCtrl',
      replace: true,
      templateUrl: 'keep/keep.tpl.html',
      link: function (scope, element /*, attrs*/ ) {

        var aUrlParser = $document[0].createElement('a');
        var secLevDomainRe = /[^.\/]+(?:\.[^.\/]{1,3})?\.[^.\/]+$/;
        var fileNameRe = /[^\/]+?(?=(?:\.[a-zA-Z0-9]{1,6}|\/|)$)/;
        var fileNameToSpaceRe = /[\/._-]/g;
        var imageWidthThreshold = 200;

        scope.addingTag = {enabled: false};

        scope.getTags = function () {
          return scope.keep && scope.keep.tagList;
        };

        scope.hasTag = function () {
          var tags = scope.getTags();
          if (tags) {
            return !!tags.length;
          } else {
            return false;
          }
        };

        scope.unkeep = function () {
          keepService.unkeep([scope.keep]);
        };

        scope.keepPublic = function () {
          keepService.keep([scope.keep], false);
        };

        scope.keepPrivate = function () {
          keepService.keep([scope.keep], true);
        };

        scope.isPrivate = function () {
          return (scope.keep && scope.keep.isPrivate) || false;
        };

        scope.isMine = function () {
          return scope.isMyBookmark(scope.keep);
        };

        scope.togglePrivate = function () {
          keepService.togglePrivate([scope.keep]);
        };

        scope.getSingleSelectedKeep = function () {
          if (scope.keep) {
            return [scope.keep];
          } else {
            return [];
          }
        };

        scope.canSend = function () {
          return installService.hasMinimumVersion('3.0.7');
        };

        scope.triggerInstall = function () {
          $rootScope.$emit('showGlobalModal','installExtension');
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
          return keep && (keep.title || formatTitleFromUrl(keep.url));
        }

        var strippedSchemeRe = /^https?:\/\//;
        var domainTrailingSlashRe = /^([^\/]*)\/$/;

        function formatDesc(url, matches) {
          if (url) {
            var strippedSchemeLen = (url.match(strippedSchemeRe) || [''])[0].length;
            url = url.substr(strippedSchemeLen).replace(domainTrailingSlashRe, '$1');
            for (var i = matches && matches.length; i--;) {
              matches[i][0] -= strippedSchemeLen;
            }
            return boldSearchTerms(url, matches);
          }
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
          if (keep) {
            return keep.siteName || keep.url;
          }
        }

        function updateTitleHtml() {
          if (scope.keep) {
            scope.keep.titleHtml = toTitleHtml(scope.keep);
          }
        }

        function updateSiteDescHtml() {
          if (scope.keep) {
            scope.keep.descHtml = formatDesc(getSite());
          }
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
          return keep && (keep.title || keep.url);
        };

        scope.getSite = getSite;

        scope.getName = function (user) {
          return [user.firstName, user.firstName].filter(function (n) {
            return !!n;
          }).join(' ');
        };

        function shouldShowSmallImage(summary) {
          return (summary.imageWidth && summary.imageWidth < imageWidthThreshold) || summary.description;
        }

        function hasSaneAspectRatio(summary) {
          var aspectRatio = summary.imageWidth && summary.imageHeight && summary.imageWidth / summary.imageHeight;
          var saneAspectRatio = aspectRatio > 0.5 && aspectRatio < 3;
          var bigEnough = summary.imageWidth + summary.imageHeight > 200;
          return bigEnough && saneAspectRatio;
        }

        scope.hasBigImage = function () {
          var keep = scope.keep;
          return keep && keep.summary && !shouldShowSmallImage(keep.summary) && hasSaneAspectRatio(keep.summary);
        };

        scope.hasSmallImage = function () {
          var keep = scope.keep;
          return keep && keep.summary && shouldShowSmallImage(keep.summary) && hasSaneAspectRatio(keep.summary);
        };

        scope.hasKeepers = function () {
          var keep = scope.keep;
          return keep && !!(keep.keepers && keep.keepers.length);
        };

        scope.showOthers = function () {
          return !scope.hasKeepers() && !! (scope.keep && scope.keep.others);
        };

        scope.showSocial = function () {
          return scope.keep && (scope.keep.others || (scope.keep.keepers && scope.keep.keepers.length > 0));
        };

        scope.showTags = function () {
          return scope.hasTag() || scope.addingTag.enabled;
        };

        scope.showAddTag = function () {
          scope.addingTag.enabled = true;
        };

        scope.onCheck = function (e) {
          // needed to prevent previewing
          e.stopPropagation();
          return scope.toggleSelect();
        };

        var tagDragMask = element.find('.kf-tag-drag-mask');
        scope.isDragTarget = false;




        // TODO: bind to 'drop' event
        scope.onTagDrop = function (tag) {
          tagService.addKeepToTag(tag, scope.keep);
          scope.isDragTarget = false;
        };




        // TODO: add/remove kf-candidate-drag-target on dragenter/dragleave
        // optionally: kf-drag-target when dragging a tag

        function sizeImage() {
          if (!scope.keep || !scope.keep.summary || !scope.keep.summary.description) {
            return;
          }

          scope.keep.summary.description = scope.keep.summary.description.substring(0, 500);

          var $sizer = angular.element('.kf-keep-description-sizer').text(scope.keep.summary.description);
          var img = { w: scope.keep.summary.imageWidth, h: scope.keep.summary.imageHeight };
          var cardWidth = element.find('.kf-keep-contents').width();
          var optimalWidth = Math.floor(cardWidth * 0.45); // ideal image size is 45% of card

          function calcHeightDelta(guessWidth) {
            function tryWidth(width) {
              $sizer[0].style.width = width;
              return $sizer[0].offsetHeight + 25; // subtitle is 25px
            }
            var imageWidth = cardWidth - guessWidth;
            var imageHeight = imageWidth / (img.w / img.h);
            var textHeight = tryWidth(guessWidth);
            var delta = (textHeight - imageHeight);
            var score = Math.abs(delta) + 0.3 * Math.abs(optimalWidth - imageWidth); // 30% penalty for distance away from optimal width
            if (imageHeight > img.h) {
              score += (imageHeight - img.h);
            }
            if (imageWidth > img.w) {
              score += (imageWidth - img.w);
            }
            return { guess: guessWidth, delta: delta, score: score, ht: textHeight, hi: imageHeight};
          }

          var i = 0;
          var low = 200, high = cardWidth - 80; // text must be minimum 200px wide, max total-80
          var guess = (high - low) / 2 + low;
          var res = calcHeightDelta(guess);
          var bestRes = res;

          while(low + i < high && bestRes.score > 20) {
            res = calcHeightDelta(low + i);
            if (bestRes.score > res.score) {
              bestRes = res;
            }
            i += 40;
          }

          var asideWidthPercent = Math.floor(((cardWidth - bestRes.guess) / cardWidth) * 100);
          var calcTextWidth = 100 - asideWidthPercent;
          var linesToShow = Math.floor((bestRes.hi / 23)); // line height
          var calcTextHeight = linesToShow * 23;

          scope.keep.sizeCard = function () {
            element.find('.kf-keep-small-image').height(bestRes.hi);
            element.find('.kf-keep-small-image').width(asideWidthPercent + '%');
            element.find('.kf-keep-info').css({
              'width': calcTextWidth + '%',
              'height': calcTextHeight + 'px'
            }).addClass('kf-dyn-positioned')
            .find('.kf-keep-description')
            .css('margin-right', '40px');
          };
        }

        function maybeSizeImage() {
          if (scope.keep && scope.keep.summary) {
            var hasResonableDesc = scope.keep.summary.description && scope.keep.summary.description.length > 60;
            var hasImage = scope.keep.summary.imageWidth > 50 && scope.keep.summary.imageHeight > 50;
            if (hasImage && hasResonableDesc && scope.hasSmallImage()) {
              scope.keep.sizeCard = null;
              scope.keep.calcSizeCard = sizeImage;
            }
          }
        }

        scope.$on('resizeImage', maybeSizeImage);

        scope.$watch('keep', maybeSizeImage);

        tagDragMask.on('dragenter', function () {
          scope.$apply(function () { scope.isDragTarget = true; });
        });

        tagDragMask.on('dragleave', function () {
          scope.$apply(function () { scope.isDragTarget = false; });
        });

        scope.isDragging = false;
        var mouseX, mouseY;
        element.on('mousemove', function (e) {
          mouseX = e.pageX - util.offset(element).left;
          mouseY = e.pageY - util.offset(element).top;
        })
        .on('dragstart', function (e) {
          scope.$apply(function () {
            $rootScope.DRAGGING_KEEP = true;
            $rootElement.addClass('kf-dragging-keep');
            element.addClass('kf-dragged');
            scope.dragKeeps({keep: scope.keep, event: e, mouseX: 20, mouseY: 50});
            scope.isDragging = true;
          });
        })
        .on('dragend', function () {
          scope.$apply(function () {
            $rootScope.DRAGGING_KEEP = false;
            $rootElement.removeClass('kf-dragging-keep');
            element.removeClass('kf-dragged');
            scope.stopDraggingKeeps();
            scope.isDragging = false;
          });
        })
        .on('drop', function (e) {
          e.preventDefault();
        });

        scope.$watch('editMode.enabled', function () {
          element.attr('draggable', true);
        });
      }
    };
  }
]);

