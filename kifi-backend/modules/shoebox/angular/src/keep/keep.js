'use strict';

angular.module('kifi')

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
  '$document', '$rootScope', '$rootElement', '$timeout', 'tagService', 'keepService', 'util',
  function ($document, $rootScope, $rootElement, $timeout, tagService, keepService, util) {
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
          var summary = scope.keep && scope.keep.summary;
          var forceBig = summary && summary.useBigLayout;
          return scope.keep && summary && (forceBig || (!shouldShowSmallImage(summary) && hasSaneAspectRatio(summary)));
        };

        scope.hasSmallImage = function () {
          var keep = scope.keep;
          return keep && keep.summary && !keep.summary.useBigLayout && shouldShowSmallImage(keep.summary) && hasSaneAspectRatio(keep.summary);
        };

        scope.hasKeepers = function () {
          var keep = scope.keep;
          return keep && !!(keep.keepers && keep.keepers.length);
        };

        scope.showSocial = function () {
          return scope.keep && (scope.keep.others || (scope.keep.keepers && scope.keep.keepers.length > 0));
        };

        scope.showTags = function () {
          return scope.isMyBookmark(scope.keep) && (scope.hasTag() || scope.addingTag.enabled);
        };

        scope.showAddTag = function () {
          scope.addingTag.enabled = true;
        };

        scope.onCheck = function (e) {
          // needed to prevent previewing
          e.stopPropagation();
          return scope.toggleSelect();
        };

        var read_times = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 30, 60];
        scope.getKeepReadTime = function () {
          var wc = scope.keep && scope.keep.summary && scope.keep.summary.wordCount;
          if (wc < 0) {
            return null;
          } else {
            var minutesEstimate = wc / 250;
            var found = _.find(read_times, function (t) { return minutesEstimate < t; });
            return found ? found + ' min' : '> 1 h';
          }
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

          var $sizer = angular.element('.kf-keep-description-sizer');
          var img = { w: scope.keep.summary.imageWidth, h: scope.keep.summary.imageHeight };
          var cardWidth = element.find('.kf-keep-contents')[0].offsetWidth;
          var optimalWidth = Math.floor(cardWidth * 0.50); // ideal image size is 45% of card

          $sizer[0].style.width = '';

          function trimDesc(desc) {
            $sizer.text(desc);
            var singleLineWidthPx = $sizer[0].offsetWidth * ($sizer[0].offsetHeight / 23);

            if (desc.length > 150) {
              // If description is quite long, trim it. We're drawing it, because for non-latin
              // languages, character length is very misleading regarding how long the text
              // will be.
              if (singleLineWidthPx > 5000) { // Roughly 8 lines at max text width
                var showRatio = 5000 / singleLineWidthPx;
                return desc.substr(0, Math.floor(showRatio * desc.length));
              }
            } else {
              if (singleLineWidthPx < cardWidth && img.w > 0.75 * cardWidth) {
                // If the text draws as one line, we may be interested in using the big image layout.
                return false;
              }
            }
            return desc;
          }

          if (!scope.keep.summary.trimmedDesc) {
            var trimmed = trimDesc(scope.keep.summary.description);
            if (trimmed === false) {
              scope.keep.summary.useBigLayout = true;
              return;
            }
            scope.keep.summary.trimmedDesc = trimmed;
          }

          $sizer.text(scope.keep.summary.trimmedDesc);

          function calcHeightDelta(guessWidth) {
            function tryWidth(width) {
              $sizer[0].style.width = width + 'px';
              //$sizer.width(width);
              var height = $sizer[0].offsetHeight + 25; // subtitle is 25px
              return height;
            }
            var imageWidth = cardWidth - guessWidth;
            var imageHeight = imageWidth / (img.w / img.h);
            var textHeight = tryWidth(guessWidth);
            var delta = textHeight - imageHeight;
            var score = Math.abs(delta) + 0.5 * Math.abs(optimalWidth - imageWidth); // 30% penalty for distance away from optimal width

            if (imageHeight > img.h) {
              score += (imageHeight - img.h);
            }
            if (imageWidth > img.w) {
              score += (imageWidth - img.w);
            }

            return { guess: guessWidth, delta: delta, score: score, ht: Math.ceil(textHeight), hi: imageHeight};
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
          //var calcTextWidth = 100 - asideWidthPercent;
          var linesToShow = Math.floor((bestRes.hi / 23)); // line height
          var calcTextHeight = linesToShow * 23 + 22; // 22px subtitle

          scope.keep.sizeCard = function () {
            var $content = element.find('.kf-keep-content-line');
            //$content.height(Math.floor(bestRes.hi) + 4); // 4px padding on image
            $content.find('.kf-keep-small-image').width(asideWidthPercent + '%');
            element.find('.kf-keep-info').css({
              'height': calcTextHeight + 'px'
            }).addClass('kf-dyn-positioned');

            $content.find('.kf-keep-image').on('error', function () {
              $content.find('.kf-keep-small-image').hide();
            });
          };
        }

        function maybeSizeImage() {
          if (scope.keep && scope.keep.summary) {
            var hasImage = scope.keep.summary.imageWidth > 50 && scope.keep.summary.imageHeight > 50;
            if (hasImage && scope.keep.summary.description && scope.hasSmallImage()) {
              scope.keep.sizeCard = null;
              scope.keep.calcSizeCard = sizeImage;
            }
          }
        }

        scope.$on('resizeImage', maybeSizeImage);

        scope.$watch('keep', function () {
          if (scope.keep && scope.keep.summary) {
            maybeSizeImage();
            if (scope.keep.calcSizeCard) {
              scope.keep.calcSizeCard();
              scope.keep.calcSizeCard = null; // only want it called once.
              if (scope.keep.sizeCard) {
                scope.keep.sizeCard();
              }
            }
          }
        });

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
            $rootElement.find('html').addClass('kf-dragging-keep');
            element.addClass('kf-dragged');
            scope.dragKeeps({keep: scope.keep, event: e, mouseX: 20, mouseY: 50});
            scope.isDragging = true;
          });
        })
        .on('dragend', function () {
          scope.$apply(function () {
            $rootScope.DRAGGING_KEEP = false;
            $rootElement.find('html').removeClass('kf-dragging-keep');
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
])

// This directive is for recos only right now, and copies a lot of code from kfKeep (above).
// TODO: consolidate/modularize the two directives so we are DRY.
.directive('kfKeepContent', ['$document', '$rootScope', 'keepActionService', 'keepService', 'recoActionService',
  function ($document, $rootScope, keepActionService, keepService, recoActionService) {
    return {
      restrict: 'A',
      scope: {
        keep: '=',
        keepPublic: '&',
        keepPrivate: '&'
      },
      replace: true,
      templateUrl: 'keep/keepContent.tpl.html',
      link: function (scope, element/*, attrs*/) {
        if (!scope.keep) {
          return;  // TODO: remove the checks in the rest of this file.
        }

        var useBigLayout = false;
        var strippedSchemeRe = /^https?:\/\//;
        var domainTrailingSlashRe = /^([^\/]*)\/$/;

        function bolded(text, start, len) {
          return text.substr(0, start) + '<b>' + text.substr(start, len) + '</b>' + text.substr(start + len);
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

        function getSite() {
          var keep = scope.keep;
          if (keep) {
            return keep.siteName || keep.url;
          }
        }

        function updateSiteDescHtml() {
          if (scope.keep) {
            scope.keep.descHtml = formatDesc(getSite());
          }
        }

        scope.showSmallImage = function () {
          return scope.keep.hasSmallImage && !useBigLayout;
        };

        scope.showBigImage = function () {
          return scope.keep.hasBigImage || (scope.keep.summary && useBigLayout);
        };

        scope.hasKeepers = function () {
          var keep = scope.keep;
          return keep && !!(keep.keepers && keep.keepers.length);
        };

        scope.isMyBookmark = function (keep) {
          return (keep && keep.isMyBookmark) || false;
        };

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

        scope.showTags = function () {
          return scope.isMyBookmark(scope.keep) && (scope.hasTag() || scope.addingTag.enabled);
        };

        scope.showAddTag = function () {
          scope.addingTag.enabled = true;
        };

        scope.isMine = function () {
          return scope.isMyBookmark(scope.keep);
        };

        scope.togglePrivate = function () {
          keepService.togglePrivate([scope.keep]);
        };

        scope.isPrivate = function () {
          return (scope.keep && scope.keep.isPrivate) || false;
        };

        scope.triggerInstall = function () {
          $rootScope.$emit('showGlobalModal','installExtension');
        };

        scope.unkeep = function () {
          keepService.unkeep([scope.keep]);
        };

        scope.getSingleSelectedKeep = function () {
          if (scope.keep) {
            return [scope.keep];
          } else {
            return [];
          }
        };

        scope.clickKeep = function (keep) {
          if (keep.keepType === 'reco') {
            recoActionService.trackClick(keep);
          }
        };

        scope.$watch('keep.url', function () {
          updateSiteDescHtml();
        });

        function sizeImage() {
          if (!scope.keep || !scope.keep.summary || !scope.keep.summary.description) {
            return;
          }

          var $sizer = angular.element('.kf-keep-description-sizer');
          var img = { w: scope.keep.summary.imageWidth, h: scope.keep.summary.imageHeight };
          var cardWidth = element.find('.kf-keep-contents')[0].offsetWidth;
          var optimalWidth = Math.floor(cardWidth * 0.50); // ideal image size is 45% of card

          $sizer[0].style.width = '';

          function trimDesc(desc) {
            $sizer.text(desc);
            var singleLineWidthPx = $sizer[0].offsetWidth * ($sizer[0].offsetHeight / 23);

            if (desc.length > 150) {
              // If description is quite long, trim it. We're drawing it, because for non-latin
              // languages, character length is very misleading regarding how long the text
              // will be.
              if (singleLineWidthPx > 5000) { // Roughly 8 lines at max text width
                var showRatio = 5000 / singleLineWidthPx;
                return desc.substr(0, Math.floor(showRatio * desc.length));
              }
            } else {
              if (singleLineWidthPx < cardWidth && img.w > 0.75 * cardWidth) {
                // If the text draws as one line, we may be interested in using the big image layout.
                return false;
              }
            }
            return desc;
          }

          if (!scope.keep.summary.trimmedDesc) {
            var trimmed = trimDesc(scope.keep.summary.description);
            if (trimmed === false) {
              useBigLayout = true;
              return;
            }
            scope.keep.summary.trimmedDesc = trimmed;
          }

          $sizer.text(scope.keep.summary.trimmedDesc);

          function calcHeightDelta(guessWidth) {
            function tryWidth(width) {
              $sizer[0].style.width = width + 'px';
              //$sizer.width(width);
              var height = $sizer[0].offsetHeight + 25; // subtitle is 25px
              return height;
            }
            var imageWidth = cardWidth - guessWidth;
            var imageHeight = imageWidth / (img.w / img.h);
            var textHeight = tryWidth(guessWidth);
            var delta = textHeight - imageHeight;
            var score = Math.abs(delta) + 0.5 * Math.abs(optimalWidth - imageWidth); // 30% penalty for distance away from optimal width

            if (imageHeight > img.h) {
              score += (imageHeight - img.h);
            }
            if (imageWidth > img.w) {
              score += (imageWidth - img.w);
            }

            return { guess: guessWidth, delta: delta, score: score, ht: Math.ceil(textHeight), hi: imageHeight};
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
          //var calcTextWidth = 100 - asideWidthPercent;
          var linesToShow = Math.floor((bestRes.hi / 23)); // line height
          var calcTextHeight = linesToShow * 23 + 22; // 22px subtitle

          scope.keep.sizeCard = function () {
            var $content = element.find('.kf-keep-content-line');
            //$content.height(Math.floor(bestRes.hi) + 4); // 4px padding on image
            $content.find('.kf-keep-small-image').width(asideWidthPercent + '%');
            element.find('.kf-keep-info').css({
              'height': calcTextHeight + 'px'
            }).addClass('kf-dyn-positioned');

            $content.find('.kf-keep-image').on('error', function () {
              $content.find('.kf-keep-small-image').hide();
            });
          };
        }

        function maybeSizeImage() {
          if (scope.keep && scope.keep.summary) {
            var hasImage = scope.keep.summary.imageWidth > 50 && scope.keep.summary.imageHeight > 50;
            if (hasImage && scope.keep.summary.description && scope.keep.hasSmallImage) {
              scope.keep.sizeCard = null;
              scope.keep.calcSizeCard = sizeImage;
            }
          }
        }

        scope.$on('resizeImage', maybeSizeImage);

        scope.$watch('keep', function () {
          if (scope.keep && scope.keep.summary) {
            maybeSizeImage();
            if (scope.keep.calcSizeCard) {
              scope.keep.calcSizeCard();
              scope.keep.calcSizeCard = null; // only want it called once.
              if (scope.keep.sizeCard) {
                scope.keep.sizeCard();
              }
            }
          }
        });

        updateSiteDescHtml();
      }
    };
  }
]);
