'use strict';

angular.module('kifi')

.directive('kfKeepCard', [
  '$analytics', 'extensionLiaison', 'util', 'installService', 'libraryService',
  'modalService', 'keepActionService', 'keepDecoratorService', 'undoService',
  function ($analytics, extensionLiaison, util, installService, libraryService,
      modalService, keepActionService, keepDecoratorService, undoService) {

    // constants for side-by-side layout image sizing heuristic, based on large screen stylesheet values
    var cardW = 496;
    var cardInnerW = cardW - 2 * 20;
    var gutterW = 20;
    var titleLineHeight = 24;
    var descLineHeight = 20;
    var metaAndOtherHeight = 38;  // meta line plus margins
    var titleCharsPerMaxWidthLine = 75;
    var descCharsPerMaxWidthLine = 48;
    var maxSizedImageW = 0.4 * (cardInnerW - gutterW);

    function calcImageSize(summary, title) {
      var url = summary.imageUrl;
      if (url) {
        var imgNaturalW = summary.imageWidth;
        var imgNaturalH = summary.imageHeight;
        var aspectRatio = imgNaturalW / imgNaturalH;
        if (imgNaturalW >= 0.6 * cardW) {  // full bleed
          return {
            url: url,
            clipBottom: aspectRatio < 0.8,  // align tall images to top instead of center
            maxDescLines: 3
          };
        }
        if (imgNaturalW >= 50 && imgNaturalH >= 50) {  // sized
          // +-----------------------------------------------+
          // |                                               |
          // |    +---------+    meta #### #### ####         |
          // |    | \     / |                                |
          // |    |  \   /  |    title #### ### #######      |
          // |    |   \ /   |                                |
          // |    |    X    |    description #### ######     |
          // |    |   / \   |    #### ## ##### ####          |
          // |    |  /   \  |    ························    |
          // |    | /     \ |    : penalized empty area :    |
          // |    +---------+    ························    |
          // |                                               |
          // +-----------------------------------------------+
          var descWideLines = (summary.description || '').length / descCharsPerMaxWidthLine;
          var titleWideLines = title.length / titleCharsPerMaxWidthLine;
          var image = {url: url, w: 0, h: 0, penalty: Infinity, clipBottom: false};
          for (var imgW = Math.min(imgNaturalW, maxSizedImageW), imgH = imgW / aspectRatio; imgW >= 60 && imgH >= 40; imgW -= 20, imgH = imgW / aspectRatio) {
            imgH = Math.min(360, imgH);
            var contentW = cardInnerW - gutterW - imgW;
            var titleLines = Math.ceil(titleWideLines * cardInnerW / contentW);
            var descLines = Math.ceil(descWideLines * cardInnerW / contentW);
            var contentH = metaAndOtherHeight + titleLineHeight * titleLines + descLineHeight * descLines;
            if (contentH > imgH) { // jshint ignore:line
              var fewerDescLines = Math.min(descLines - 2, Math.ceil((contentH - imgH) / descLineHeight));
              descLines -= fewerDescLines;
              contentH -= fewerDescLines * descLineHeight;
            }
            var penalty = imgH > contentH ? (imgH - contentH) * contentW : (contentH - imgH) * imgW;
            if (penalty < image.penalty) { // jshint ignore:line
              image.w = imgW;
              image.h = imgH;
              image.penalty = penalty;
              image.clipBottom = true;
              image.maxDescLines = descLines;
            }
          }
          return image;
        }
      }
    }

    return {
      restrict: 'A',
      scope: {
        keep: '=',
        library: '=',
        currentPageOrigin: '@',
        selectable: '=',
        toggleSelect: '&',
        isSelected: '&',
        keepCallback: '&',
        clickCallback: '&'
      },
      replace: true,
      templateUrl: 'keep/keepCard.tpl.html',
      link: function (scope) {

        //
        // Scope data.
        //

        (function (keep) {
          scope.youtubeId = util.getYoutubeIdFromUrl(keep.url);
          scope.keepSource = keep.siteName || keep.url.replace(/^(?:[a-z]*:\/\/)?(?:www\.)?([^\/]*).*$/, '$1');
          scope.displayTitle = keep.title || keep.summary && keep.summary.title || util.formatTitleFromUrl(keep.url);
          scope.image = scope.youtubeId ? null : calcImageSize(keep.summary, scope.displayTitle);
        }(scope.keep));

        //
        // Internal methods.
        //

        function setHowKept() {
          scope.howKept = scope.keep.keeps.length ?
            _.all(scope.keep.keeps, {visibility: 'secret'}) ?
            'private' : 'public' : null;
        }

        //
        // Scope methods.
        //

        scope.onWidgetLibraryClicked = function (clickedLibrary) {
          // Unkeep. TODO: only if unkeep button was clicked
          if (clickedLibrary && scope.keptToLibraryIds.indexOf(clickedLibrary.id) >= 0) {
            var keepToUnkeep = _.find(scope.keep.keeps, { libraryId: clickedLibrary.id });
            keepActionService.unkeepFromLibrary(clickedLibrary.id, keepToUnkeep.id).then(function () {
              var removedKeeps;
              if (clickedLibrary.id === scope.keep.libraryId) {
                scope.keep.makeUnkept();
              } else {
                removedKeeps = _.remove(scope.keep.keeps, { libraryId: clickedLibrary.id });
              }

              libraryService.addToLibraryCount(clickedLibrary.id, -1);
              scope.$emit('keepRemoved', { url: scope.keep.url }, clickedLibrary);

              undoService.add('keep deleted', function () {  // TODO: rekeepToLibrary endpoint that takes a keep ID
                keepActionService.keepToLibrary([{url: scope.keep.url}], clickedLibrary.id).then(function () {
                  if (removedKeeps) {
                    scope.keep.keeps.push(removedKeeps[0]);
                  } else {
                    scope.keep.makeKept();
                  }

                  libraryService.addToLibraryCount(clickedLibrary.id, 1);
                  scope.$emit('keepAdded', [scope.keep], clickedLibrary);
                })['catch'](modalService.openGenericErrorModal);
              });
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

        scope.shareAction = function () {
          if (installService.hasMinimumVersion('3.0.7')) {
            extensionLiaison.openDeepLink(scope.keep.url, '/messages:all#compose');
          } else {
            modalService.open({
              template: 'common/modal/installExtensionModal.tpl.html',
              scope: scope
            });
            // installService.triggerInstall(function () {
            //   modalService.open({
            //     template: 'common/modal/installExtensionErrorModal.tpl.html'
            //   });
            // });
          }
        };

        scope.trackTweet = function () {
          $analytics.eventTrack('user_clicked_page', {type: 'library', action: 'clickedViewOriginalTweetURL'});
        };

        //
        // Watches and listeners.
        //

        scope.$watchCollection(function () {
          return _.pluck(scope.keep.keeps, 'visibility');
        }, setHowKept);

        scope.$watch('keep.isMyBookmark', setHowKept);

        scope.$watchCollection(function () {
          return _.pluck(scope.keep.keeps, 'libraryId');
        }, function (libraryIds) {
          scope.keptToLibraryIds = libraryIds;
        });

        scope.$watch(function () {
          return libraryService.getOwnInfos().length;
        }, function (newVal) {
          if (newVal) {
            scope.libraries = _.reject(libraryService.getOwnInfos(), {id: scope.keep.libraryId});
          }
        });
      }
    };
  }
]);
