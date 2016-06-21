'use strict';

angular.module('kifi')

.directive('kfKeepCard', [
  '$state', '$analytics', '$q', 'extensionLiaison', 'util', 'installService', 'libraryService',
  'modalService', 'keepActionService', '$location', 'undoService', '$rootScope', 'profileService',
  '$injector', '$filter',
  function ($state, $analytics, $q, extensionLiaison, util, installService, libraryService,
      modalService, keepActionService, $location, undoService, $rootScope, profileService,
      $injector, $filter) {

    var maxMembersPerEntity = 4;

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

    function calcImageSize(summary, title, galleryView) { // jshint ignore:line
      var url = summary.imageUrl,
          image;
      if (url) {
        var imgNaturalW = summary.imageWidth;
        var imgNaturalH = summary.imageHeight;
        var aspectRatio = imgNaturalW / imgNaturalH;
        if (imgNaturalW >= 0.6 * cardW) {  // full bleed
          return {
            url: url,
            clipBottom: aspectRatio < 0.8  // align tall images to top instead of center
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
            if (penalty < (image ? image.penalty : Infinity)) { // jshint ignore:line
              image = {url: url, w: imgW, h: imgH, penalty: penalty, clipBottom: true, maxDescLines: descLines};
            }
          }
          if (!galleryView && image) {
            image.w = image.h = null;
            image.maxDescLines = 2;
          }
          return image;
        }
      }
    }

    return {
      restrict: 'A',
      scope: {
        keep: '=kfKeepCard',
        boxed: '@',
        currentPageOrigin: '@',
        keepCallback: '&',
        clickCallback: '&',
        deleteCallback: '&',
        removeImageCallback: '&',
        forceGalleryView: '=',
        isFirstItem: '=',
        maxInitialComments: '='
      },
      replace: true,
      templateUrl: 'keep/keepCard.tpl.html',
      link: function (scope) {
        //
        // Internal methods.
        //

        function setHowKept() {
          scope.howKept = _.any(scope.keep.keeps, _.identity) ?
            _.all(scope.keep.keeps, {visibility: 'secret'}) ?
            'private' : 'public' : null;
        }

        function unkeepFromLibrary(event, keep) {
          if (keep.libraryId && keep.id) {
            keep.unkept = true;
            keepActionService.unkeepFromLibrary(keep.libraryId, keep.id)['catch'](function (err) {
              keep.unkept = false;
              modalService.openGenericErrorModal(err);
            });
          }
        }

        //
        // Scope methods.
        //
        scope.toggleExpandCard = function () {
          scope.galleryView = scope.forceGalleryView || !scope.galleryView;
        };

        scope.editKeepNote = function (event, keep) {
          if (keep.id !== scope.keep.id || !scope.canEditKeep) {
            return;
          }
          scope.galleryView = true;

          var keepEl = angular.element(event.target).closest('.kf-keep-card');
          var editor = keepEl.find('.kf-knf-editor');
          if (!editor.length) {
            var noteEl = keepEl.find('.kf-keep-card-note');
            var keepLibraryId = keep.library && keep.library.id;
            $injector.get('keepNoteForm').init(noteEl, scope.keep.displayNote, keepLibraryId, keep.pubId, function update(noteText) {
              keep.note = noteText;
            });
          } else {
            editor.focus();
          }
        };

        scope.editKeepTitle = function (event, keep) {
          if (keep.id !== scope.keep.id || !scope.canEditKeep) {
            return;
          }
          modalService.open({
            template: 'keep/editKeepTitleModal.tpl.html',
            modalData: {
              keep: scope.keep
            }
          });
        };

        scope.onWidgetLibraryClicked = function (clickedLibrary) {
          if (scope.keptToLibraryIds.indexOf(clickedLibrary.id) >= 0) {
            // Unkeep. TODO: only if unkeep button was clicked
            var keepToUnkeep = _.find(scope.keep.keeps, { libraryId: clickedLibrary.id });
            keepActionService.unkeepFromLibrary(clickedLibrary.id, keepToUnkeep.id).then(function () {
              var removedKeeps;
              if (clickedLibrary.id === scope.keep.libraryId) {
                scope.keep.unkept = true;
              } else {
                removedKeeps = _.remove(scope.keep.keeps, { libraryId: clickedLibrary.id });
              }
              scope.keep.keepersTotal--;

              libraryService.addToLibraryCount(clickedLibrary.id, -1);
              scope.$emit('keepRemoved', { url: scope.keep.url }, clickedLibrary);

              undoService.add('Keep deleted.', function () {  // TODO: rekeepToLibrary endpoint that takes a keep ID
                keepActionService.keepToLibrary([{url: scope.keep.url}], clickedLibrary.id).then(function () {
                  if (removedKeeps) {
                    scope.keep.keeps.push(removedKeeps[0]);
                  } else {
                    scope.keep.unkept = false;
                  }
                  scope.keep.keepersTotal++;

                  libraryService.addToLibraryCount(clickedLibrary.id, 1);
                  $rootScope.$broadcast('keepAdded', [scope.keep], clickedLibrary);
                })['catch'](modalService.openGenericErrorModal);
              });
            })['catch'](modalService.openGenericErrorModal);

          } else {
            // Keep.
            var fetchKeepInfoCallback = function (fullKeep) {
              libraryService.fetchLibraryInfos(true);
              libraryService.addToLibraryCount(clickedLibrary.id, 1);

              scope.keep.keeps = fullKeep.keeps;

              scope.$emit('keepAdded', [fullKeep], clickedLibrary);
            };
            var libraryInvitePromise = null;
            if (!clickedLibrary.membership || clickedLibrary.membership === 'read_only') {
              libraryInvitePromise = libraryService.joinLibrary(clickedLibrary.id, null, clickedLibrary.membership && clickedLibrary.membership.subscribed);
            }
            $q.when(libraryInvitePromise).then(function () {
              keepActionService.keepToLibrary([{title: scope.keep.title, url: scope.keep.url}], clickedLibrary.id).then(function (result) {
                if ((!result.failures || !result.failures.length) && result.alreadyKept.length === 0) {
                  return keepActionService.fetchFullKeepInfo(result.keeps[0]).then(fetchKeepInfoCallback);
                }
              })['catch'](modalService.openGenericErrorModal);
            });
          }
        };

        scope.shareAction = function () {
          if (installService.hasMinimumVersion('3.0.7')) {
            extensionLiaison.openDeepLink(scope.keep.url, '/messages:all#compose');
          } else {
            scope.triggerInstall = installService.triggerInstall;
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

        scope.maybeOpenExt = function (event) {
          var canAddComment = scope.keep.permissions && scope.keep.permissions.indexOf('add_message') !== -1;
          if (canAddComment && installService.hasMinimumVersion('3.0.7')) {
            event.preventDefault();
            extensionLiaison.openDeepLink(scope.keep.url, '/messages/' + scope.keep.pubId);
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
        [
          $rootScope.$on('onWidgetLibraryClicked', function(event, args) {
            scope.onWidgetLibraryClicked(args.clickedLibrary);
          }),
          $rootScope.$on('prefsChanged', function() {
            scope.galleryView = scope.forceGalleryView || !profileService.prefs.use_minimal_keep_card;
            scope.globalGalleryView = scope.galleryView;
          }),
          $rootScope.$on('cardStyleChanged', function(s, style) {
            scope.galleryView = scope.forceGalleryView || !style.use_minimal_keep_card;
            scope.globalGalleryView = scope.galleryView;
          })
        ].forEach(function (deregister) {
          scope.$on('$destroy', deregister);
        });

        //
        // Scope data.
        //

        (function (keep) {
          scope.youtubeId = util.getYoutubeIdFromUrl(keep.url);
          scope.keepSource = keep.siteName || keep.url.replace(/^(?:[a-z]*:\/\/)?(?:www\.)?([^\/]*).*$/, '$1');
          var updateTitle = function () {
              scope.displayTitle = keep.title || keep.summary && keep.summary.title || util.formatTitleFromUrl(keep.url);
          };
          updateTitle();
          scope.$watch('keep.title', updateTitle);
          scope.defaultDescLines = 4;
          scope.me = profileService.me;
          scope.showActivityEvents = keep.activity;
          scope.showOriginLibrary = scope.currentPageOrigin !== 'libraryPage' &&
            keep.library && keep.library.visibility !== 'discoverable' && keep.library.kind === 'system_secret';
          scope.showAddRecipients = keep.permissions.indexOf('add_participants') !== -1 || keep.permissions.indexOf('add_libraries') !== -1;
          // Don't change until the link is updated to be a bit more secure:
          scope.galleryView = scope.forceGalleryView || !profileService.prefs.use_minimal_keep_card;
          scope.globalGalleryView = scope.galleryView;


          scope.maxMembersPerEntity = maxMembersPerEntity;
          scope.totalMemberCount = keep.members.users.length + keep.members.libraries.length + keep.members.emails.length;
          scope.leftoverMembers = keep.members.users.slice(maxMembersPerEntity)
            .concat(keep.members.libraries.slice(maxMembersPerEntity))
            .concat(keep.members.emails.slice(maxMembersPerEntity));

          var updateNote = function () {
            var noteMayHaveFallback = keep.sourceAttribution && (keep.sourceAttribution.twitter || keep.sourceAttribution.slack);
            var noteHasSubstance = function (note) {
              if (!note) { return false; }
              var parts = note.split(/\[#((?:\\.|[^\]])*)\]/g); // splitting on hashtags
              var textPortion = '';
              for (var i = 0; i < parts.length; i += 2) { textPortion += parts[i].trim(); }
              return textPortion.length > 0;
            };

            if (noteMayHaveFallback && !noteHasSubstance(keep.note)) {
              if (keep.sourceAttribution.twitter) {
                scope.keep.displayNote = keep.sourceAttribution.twitter.tweet.text;
                scope.keep.noteAttribution = 'Twitter';
              } else if (keep.sourceAttribution.slack) {
                var html = $filter('slackText')(keep.sourceAttribution.slack.message.text);
                if (html) {
                  scope.keep.displayNote = html;
                  scope.keep.noteAttribution = 'Slack';
                }
              }
            } else {
              scope.keep.displayNote = keep.note;
              scope.keep.noteAttribution = '';
            }
          };
          scope.$watch('keep.note', updateNote);

          var libraryPermissions = (keep.library && keep.library.permissions) || [];
          var keepPermissions = keep.permissions || [];
          var keepUserId = keep.author && keep.author.kind === 'kifi' && keep.author.id;
          scope.canRemoveKeepFromLibrary = (
            (keepUserId === scope.me.id && libraryPermissions.indexOf('remove_own_keeps') !== -1) ||
            libraryPermissions.indexOf('remove_other_keeps') !== -1
          );
          scope.canEditKeep = keepPermissions.indexOf('edit_keep') !== -1;

          var setImage = function(galleryView) {
            scope.image = scope.youtubeId ? null : calcImageSize(keep.summary, scope.displayTitle, galleryView);
            scope.defaultDescLines = galleryView ? 4 : 2;
          };
          setImage(scope.galleryView);
          scope.$watch('galleryView', setImage);

          if (keepUserId) {
            // don't repeat the user at the top of the keep card in the keeper list
            _.remove(keep.keepers, {id: keepUserId});
          }
          if (keep.libraryId && $state.includes('libraries')) {
            // if on a library page, don't show the library
            // TODO: The controller should not decide what data is SHOWN, that's
            // the template's responsibility...
            _.remove(keep.libraries, function (pair) {
              return (pair[0] && pair[0].id) === keep.libraryId;
            });
          }
          // keep.recipients is on the new format, not compatible with keep.keepers and keep.libraries
          if (!keep.recipients && keep.libraries) {
            // associate libraries with connected keepers
            var libsByUserId = _(keep.libraries).reverse().indexBy(function (pair) { return pair[1].id; }).mapValues(0).value();
            _.each(keep.keepers, function (keeper) {
              var keeperId = keeper && keeper.id;
              keeper.library = libsByUserId[keeperId];
            });
            // show additional libraries after connected keepers
            var keepersById = _.indexBy(keep.keepers, 'id');
            _.each(keep.libraries, function (pair) {
              var user = pair[1];
              var userId = user && user.id;
              if (!keepersById[userId]) {
                keepersById[userId] = user;
                user.library = pair[0];
                keep.keepers.push(user);
              }
            });
          }
          _.remove(keep.keepers, {pictureName: '0.jpg'});

          var updateMenuItems = function () {
            scope.menuItems = [];
            if (scope.canEditKeep) {
              scope.menuItems.push({
                title: 'Edit Title',
                action: scope.editKeepTitle.bind(scope)
              });
              var removeImageCallback = scope.removeImageCallback();
              if (keep.summary && keep.summary.imageUrl && removeImageCallback) {
                scope.menuItems.push({title: 'Remove Image', action: removeImageCallback});
              }
            }
            if (scope.canRemoveKeepFromLibrary) {
              var deleteCallback = scope.deleteCallback() || unkeepFromLibrary;
              scope.menuItems.push({title: 'Delete Keep', action: deleteCallback});
            }
          };
          updateMenuItems();
          scope.$watch('keep.note', updateMenuItems);
          scope.$watch('keep.summary.imageUrl', updateMenuItems);
        }(scope.keep));
      }
    };
  }
]);
