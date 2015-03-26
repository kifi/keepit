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

.directive('kfKeepCard', [
  '$rootScope', 'installService', 'libraryService', 'modalService', 'util', '$analytics',
  function ($rootScope, installService, libraryService, modalService, util, $analytics) {
    return {
      restrict: 'A',
      scope: {
        keep: '=',
        library: '=',
        currentPageOrigin: '@',
        editMode: '=',
        editOptions: '&',
        toggleSelect: '&',
        isSelected: '&',
        keepCallback: '&',
        clickCallback: '&'
      },
      replace: true,
      templateUrl: 'keep/keepCard.tpl.html',
      link: function (scope, element/*, attrs*/) {
        if (!scope.keep) {
          return;
        }

        scope.isMyLibrary = scope.library && libraryService.isMyLibrary(scope.library);

        //
        // Internal data.
        //
        var useBigLayout = false;
        var strippedSchemeRe = /^https?:\/\//;
        var domainTrailingSlashRe = /^([^\/]*)\/$/;

        //
        // Scope data.
        //
        scope.addingTag = {enabled: false};
        scope.userLoggedIn = $rootScope.userLoggedIn;
        scope.cardType = '';
        scope.youtubeId = '';

        //
        // Internal methods.
        //
        function init() {
          updateSiteDescHtml(scope.keep);
          setCardType(scope.keep.url);
        }

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

        function setCardType(url) {
          scope.youtubeId = util.getYoutubeIdFromUrl(url);
          if (scope.youtubeId) {
            scope.cardType = 'youtube';
          } else if (scope.keep.hasBigImage) {
            scope.cardType = 'big';
          } else {
            scope.cardType = 'small';
          }
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

        function updateSiteDescHtml(keep) {
          keep.descHtml = formatDesc(keep.siteName || keep.url);
        }

        function sizeImage(keep) {
          if (!keep || !keep.summary || !keep.summary.description) {
            return;
          }

          var $sizer = angular.element('.kf-keep-description-sizer');
          var img = { w: keep.summary.imageWidth, h: keep.summary.imageHeight };
          var cardWidth = element.find('.kf-keep-contents')[0].offsetWidth;
          var optimalWidth = Math.floor(cardWidth * 0.50); // ideal image size is 45% of card
          var textHeight = parseInt($sizer.css('line-height'), 10);

          $sizer[0].style.width = '';

          function trimDesc(desc) {
            $sizer.text(desc);
            var singleLineWidthPx = $sizer[0].offsetWidth * ($sizer[0].offsetHeight / textHeight);

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

          if (!keep.summary.trimmedDesc) {
            var trimmed = trimDesc(keep.summary.description);
            if (trimmed === false) {
              if (scope.cardType === 'small') {
                scope.cardType = 'big';
              }
              useBigLayout = true;
              return;
            }
            keep.summary.trimmedDesc = trimmed;
          }

          $sizer.text(keep.summary.trimmedDesc);

          function calcHeightDelta(guessWidth) {
            function tryWidth(width) {
              $sizer[0].style.width = width + 'px';
              //$sizer.width(width);
              var height = $sizer[0].offsetHeight;
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
          var low = 200, high = cardWidth - 140; // text must be minimum 200px wide, max total-80
          var guess = (high - low) / 2 + low;
          var res = calcHeightDelta(guess);
          var bestRes = res;

          while (low + i < high && bestRes.score > 20) {
            res = calcHeightDelta(low + i);
            if (bestRes.score > res.score) {
              bestRes = res;
            }
            i += 40;
          }

          var asideWidthPercent = Math.floor(((cardWidth - bestRes.guess) / cardWidth) * 100);
          var linesToShow = Math.ceil((bestRes.hi / textHeight)); // line height
          scope.linesToShow = linesToShow;

          keep.sizeCard = function () {
            var $content = element.find('.kf-keep-content-line');
            //$content.height(Math.floor(bestRes.hi) + 4); // 4px padding on image
            $content.find('.kf-keep-small-image').width(asideWidthPercent + '%');
            $content.find('.kf-keep-image').on('error', function () {
              $content.find('.kf-keep-small-image').hide();
            });
          };
        }

        function maybeSizeImage(keep) {
          if (keep && keep.summary) {
            var hasImage = keep.summary.imageWidth > 110 && keep.summary.imageHeight > 110;
            if (hasImage && keep.summary.description && keep.hasSmallImage) {
              keep.sizeCard = null;
              keep.calcSizeCard = sizeImage;
            }
          }
        }

        //
        // Scope methods.
        //
        scope.getReadOnlyTags = function (keep) {
          return (!scope.isTaggable(keep) && !_.isEmpty(keep.tags)) ? keep.tags : [];
        };

        scope.hasReadyOnlyTags = function (keep) {
          return scope.getReadOnlyTags(keep).length > 0;
        };

        scope.isTaggable = function (keep) {
          return scope.isMyLibrary && keep.isMyBookmark;
        };

        scope.showSmallImage = function (keep) {
          var hasImage = keep.summary.imageWidth > 110 && keep.summary.imageHeight > 110;
          return hasImage && keep.hasSmallImage && !useBigLayout && scope.cardType === 'small';
        };

        scope.showBigImage = function (keep) {
          var bigImageReady = keep.hasBigImage || (keep.summary && useBigLayout);
          return bigImageReady && scope.cardType === 'big';
        };

        scope.hasTag = function (keep) {
          return keep.hashtags && keep.hashtags.length > 0;
        };

        scope.showTags = function (keep) {
          return scope.hasReadyOnlyTags(keep) ||
            (scope.isTaggable(keep) && (scope.hasTag(keep) || scope.addingTag.enabled));
        };

        scope.showAddTag = function () {
          scope.addingTag.enabled = true;
        };

        scope.getSingleSelectedKeep = function (keep) {
          return [keep];
        };

        scope.onCheck = function (e, keep) {
          // needed to prevent previewing
          e.stopPropagation();
          return scope.toggleSelect(keep);
        };

        scope.triggerInstall = function () {
          installService.triggerInstall(function () {
            modalService.open({
              template: 'common/modal/installExtensionErrorModal.tpl.html'
            });
          });
        };

        scope.showInstallExtensionModal = function () {
          modalService.open({
            template: 'common/modal/installExtensionModal.tpl.html',
            scope: scope
          });
        };

        scope.trackTweet = function () {
          $analytics.eventTrack('user_clicked_page', {type: 'library', action: 'clickedViewOriginalTweetURL'});
        };

        //
        // Watches and listeners.
        //
        scope.$watch('keep.url', function () {
          updateSiteDescHtml(scope.keep);
        });

        scope.$on('resizeImage', function () {
          maybeSizeImage(scope.keep);
        });

        scope.$watch('keep', function () {
          if (scope.keep.summary) {
            maybeSizeImage(scope.keep);
            if (scope.keep.calcSizeCard) {
              scope.keep.calcSizeCard(scope.keep);
              scope.keep.calcSizeCard = null; // only want it called once.
              if (scope.keep.sizeCard) {
                scope.keep.sizeCard();
              }
            }
          }
        });

        scope.$watch(function () {
          return libraryService.getOwnInfos().length;
        }, function (newVal) {
          if (newVal) {
            scope.libraries = _.reject(libraryService.getOwnInfos(), {id: scope.keep.libraryId});
          }
        });


        //
        // On link.
        //
        init();
      }
    };
  }
])

.directive('kfKeepMasterButton', ['keepActionService', 'keepDecoratorService', 'libraryService', 'modalService',
  function (keepActionService, keepDecoratorService, libraryService, modalService) {
    return {
      restrict: 'A',
      scope: {
        keep: '=',
        library: '=',
        libraries: '='
      },
      replace: false,
      templateUrl: 'keep/keepMasterButton.tpl.html',
      link: function (scope/*, element, attrs*/) {
        //
        // Internal methods.
        //
        function init() {
          updateKeepStatus();
        }

        function updateKeepStatus() {
          scope.isNotKept = scope.isKeptPublic = scope.isKeptPrivate = false; // reset
          if (scope.keep.keeps.length === 0) {
            scope.isNotKept = true;
          } else {
            if (_.all(scope.keep.keeps, { visibility: 'secret' })) {
              scope.isKeptPrivate = true;
            } else {
              scope.isKeptPublic = true;
            }
          }
        }


        //
        // Scope methods.
        //
        scope.onWidgetLibraryClicked = function (clickedLibrary) {
          // Unkeep. TODO: only if unkeep button was clicked
          if (clickedLibrary && scope.keptToLibraryIds.indexOf(clickedLibrary.id) >= 0) {
            var keepToUnkeep = _.find(scope.keep.keeps, { libraryId: clickedLibrary.id });
            keepActionService.unkeepFromLibrary(clickedLibrary.id, keepToUnkeep.id).then(function () {
              if (clickedLibrary.id === scope.keep.libraryId) {
                scope.keep.makeUnkept();
              } else {
                _.remove(scope.keep.keeps, { libraryId: clickedLibrary.id });
              }

              libraryService.addToLibraryCount(clickedLibrary.id, -1);
              scope.$emit('keepRemoved', { url: scope.keep.url }, clickedLibrary);
            })['catch'](modalService.openGenericErrorModal);

          // Keep.
          } else {
            var fetchKeepInfoCallback = function (fullKeep) {
              libraryService.fetchLibraryInfos(true);
              libraryService.addToLibraryCount(clickedLibrary.id, 1);

              scope.keep.keeps = fullKeep.keeps;

              var keep = new keepDecoratorService.Keep(fullKeep);
              keep.buildKeep(keep);
              keep.makeKept();
              scope.$emit('keepAdded', [keep], clickedLibrary);
            };

            var keepToLibrary;
            if (scope.keep && scope.keep.id) {
              keepToLibrary = keepActionService.copyToLibrary([scope.keep.id], clickedLibrary.id).then(function (result) {
                if (result.successes.length > 0) {
                  return keepActionService.fetchFullKeepInfo(scope.keep).then(fetchKeepInfoCallback);
                }
              });
            } else {
              // When there is no id on the keep object (e.g., recommendations), use the keep's url instead.
              var keepInfo = { title: scope.keep.title, url: scope.keep.url };
              keepToLibrary = keepActionService.keepToLibrary([keepInfo], clickedLibrary.id).then(function (result) {
                if ((!result.failures || !result.failures.length) && result.alreadyKept.length === 0) {
                  return keepActionService.fetchFullKeepInfo(result.keeps[0]).then(fetchKeepInfoCallback);
                }
              });
            }

            keepToLibrary['catch'](modalService.openGenericErrorModal);
          }
        };


        //
        // Watches and listeners.
        //
        scope.$watchCollection(function () {
          return _.pluck(scope.keep.keeps, 'visibility');
        }, updateKeepStatus);

        scope.$watch('keep.isMyBookmark', updateKeepStatus);

        scope.$watchCollection(function () {
          return _.pluck(scope.keep.keeps, 'libraryId');
        }, function (libraryIds) {
          scope.keptToLibraryIds = libraryIds;
        });


        init();
      }
    };
  }
])

.directive('kfKeepShareButton', [
  'extensionLiaison', 'installService',
  function (extensionLiaison, installService) {
    return {
      restrict: 'A',
      scope: {
        keep: '=',
        showInstallExtensionModal: '&'
      },
      replace: false,
      templateUrl: 'keep/keepShareButton.tpl.html',
      link: function (scope) {
        scope.shareAction = function () {
          if (installService.hasMinimumVersion('3.0.7')) {
            extensionLiaison.openDeepLink(scope.keep.url, '/messages:all#compose');
          } else {
            scope.showInstallExtensionModal();
          }
        };
      }
    };
  }
])

.directive('kfKeepTagButton', [
  function () {
    return {
      restrict: 'A',
      scope: {
        keep: '=',
        showAddTag: '&'
      },
      replace: false,
      templateUrl: 'keep/keepTagButton.tpl.html'
    };
  }
]);
