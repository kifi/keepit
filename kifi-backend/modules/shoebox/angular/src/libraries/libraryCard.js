'use strict';

angular.module('kifi')

.directive('kfLibraryCard', [
  '$FB', '$http', '$location', '$q', '$rootScope', '$state', '$stateParams', '$timeout', '$twitter', '$window',
  'env', 'friendService', 'libraryService', 'modalService','profileService', 'platformService', 'signupService',
  'routeService', 'util',
  function ($FB, $http, $location, $q, $rootScope, $state, $stateParams, $timeout, $twitter, $window,
            env, friendService, libraryService, modalService, profileService, platformService, signupService,
            routeService, util) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        username: '=',
        librarySlug: '=',
        recommendation: '=',
        loading: '=',
        toggleEdit: '=',
        librarySearch: '=',
        followCallback: '&',
        clickLibraryCallback: '&'
      },
      templateUrl: 'libraries/libraryCard.tpl.html',
      link: function (scope, element/*, attrs*/) {
        //
        // Internal data.
        //
        var uriRe = /(?:\b|^)((?:(?:(https?|ftp):\/\/|www\d{0,3}[.])?(?:[a-z0-9](?:[-a-z0-9]*[a-z0-9])?\.)+(?:com|edu|biz|gov|in(?:t|fo)|mil|net|org|name|coop|aero|museum|a[cdegilmoqrstuz]|b[abefghimnrtyz]|c[acdfghiklmnoruxyz]|d[ejko]|e[cegst]|f[ijkmor]|g[befghilmnpqrstu]|h[kmnru]|i[delmnorst]|j[eop]|k[eghrwyz]|l[bciktuvy]|m[cdghkmnoqrstuwxyz]|n[acfilouz]|om|p[aeghklmnrty]|qa|r[eouw]|s[abcdeghikmnotuvz]|t[cdfhjmnoprtvwz]|u[agkmsyz]|v[eginu]|wf|y[t|u]|z[amrw]\b))(?::[0-9]{1,5})?(?:\/(?:[^\s()<>]*[^\s`!\[\]{};:.'",<>?«»()“”‘’]|\((?:[^\s()<>]+|(?:\([^\s()<>]+\)))*\))*|\b))(?=[\s`!()\[\]{};:.'",<>?«»“”‘’]|$)/;  // jshint ignore:line
        var authToken = $location.search().authToken || '';
        var prevQuery = '';
        var headerLinksShifted = false;
        var headerLinksWidth = '60px';
        var keepShowingSearchBar = false;
        var coverImageFile;
        var coverImagePos;
        var coverImageMoveTracked;
        var URL = $window.URL || $window.webkitURL;

        var headerLinksElement = angular.element('.kf-header-right');
        var searchFollowElement = angular.element('.kf-keep-lib-footer-button-follow-in-search');
        var libraryBodyElement = angular.element('.kf-library-body');

        //
        // Scope data.
        //
        scope.isUserLoggedOut = $rootScope.userLoggedIn === false;
        scope.clippedDescription = false;
        scope.followersToShow = 0;
        scope.numAdditionalFollowers = 0;
        scope.editKeepsText = 'Edit Keeps';
        scope.librarySearchInProgress = false;
        scope.search = { 'text': $stateParams.q || '' };
        scope.pageScrolled = false;

        //
        // Internal methods.
        //
        function adjustFollowerPicsSize() {
          var statsAndFollowersDiv = element.find('.kf-keep-lib-stats-and-followers');
          var followerPicsDiv = element.find('.kf-keep-lib-follower-pics');
          var widthPerFollowerPic = 50;

          var parentWidth = statsAndFollowersDiv.width();

          // 250px needed for other stuff in the parent's width.
          var maxFollowersToShow = Math.floor((parentWidth - 250) / widthPerFollowerPic);
          scope.numAdditionalFollowers = 0;

          // If we only have one additional follower that we can't fit in, then we can fit that one
          // in if we don't show the additional-number-of-followers circle.
          if (maxFollowersToShow === scope.library.numFollowers - 1) {
            maxFollowersToShow++;
          }

          scope.$evalAsync(function () {
            if (maxFollowersToShow < 1) {
              scope.followersToShow = [];
              scope.numAdditionalFollowers = scope.library.numFollowers;
            } else if (maxFollowersToShow >= scope.library.numFollowers) {
              scope.followersToShow = scope.library.followers;
            } else {
              scope.followersToShow = scope.library.followers.slice(0, maxFollowersToShow);
              scope.numAdditionalFollowers = scope.library.numFollowers - maxFollowersToShow;
            }

            followerPicsDiv.width(maxFollowersToShow >= 1 ? maxFollowersToShow * widthPerFollowerPic : 0);
          });
        }

        function trackShareEvent(action) {
          $timeout(function () {
            $rootScope.$emit('trackLibraryEvent', 'click', { action: action });
          });
        }

        function processUrls(text) {
          var parts = (text || '').split(uriRe);

          for (var i = 1; i < parts.length; i += 3) {
            var uri = parts[i];
            var scheme = parts[i+1];
            var url = (scheme ? '' : 'http://') + util.htmlEscape(uri);

            parts[i] = '<a target="_blank" href="' + url + '">' + url;
            parts[i+1] = '</a>';
            parts[i-1] = util.htmlEscape(parts[i-1]);
          }
          parts[parts.length-1] = util.htmlEscape(parts[parts.length-1]);

          return parts.join('');
        }

        // Data augmentation.
        // TODO(yiping): make new libraryDecoratorService to do this. Then, DRY up the code that is
        // currently in nav.js too.
        function augmentData() {

          // Figure out whether this library is a library that the user has been invited to.
          function getInvitePromise() {
            var promise = null;

            if (libraryService.invitedSummaries.length) {
              promise = $q.when(libraryService.invitedSummaries);
            } else {
              promise = libraryService.fetchLibrarySummaries(true).then(function () {
                return libraryService.invitedSummaries;
              });
            }

            return promise.then(function (invitedSummaries) {
              var maybeLib = _.find(invitedSummaries, { 'id' : scope.library.id });

              if (maybeLib) {
                return {
                  inviterName: maybeLib.inviter.firstName + ' ' + maybeLib.inviter.lastName,
                  actedOn: false
                };
              } else {
                return null;
              }
            });
          }

          // Libraries created with the extension do not have the description field.
          if (!scope.library.description) {
            scope.library.description = '';
          }

          // TODO(yiping): get real owner data when owner is not user.
          if (!scope.library.owner) {
            scope.library.owner = profileService.me;
          }

          if (scope.library.owner) {
            scope.library.owner.picUrl = friendService.getPictureUrlForUser(scope.library.owner);
            scope.library.owner.profileUrl = routeService.getProfileUrl(scope.library.owner.username);
          }

          scope.library.followers = scope.library.followers || [];
          scope.library.followers.forEach(augmentFollower);

          var maxLength = 150;
          if (scope.library.description.length > maxLength && !scope.isUserLoggedOut) {
            // Try to chop off at a word boundary, using a simple space as the word boundary delimiter.
            var clipLastIndex = maxLength;
            var lastSpaceIndex = scope.library.description.lastIndexOf(' ', maxLength);
            if (lastSpaceIndex !== -1) {
              clipLastIndex = lastSpaceIndex + 1;  // Grab the space too.
            }

            scope.library.shortDescription = processUrls(scope.library.description.substr(0, clipLastIndex));
            scope.clippedDescription = true;
          }
          scope.library.formattedDescription = '<p>' + processUrls(scope.library.description).replace(/\n+/g, '<p>');

          scope.library.shareUrl = env.origin + scope.library.url;
          scope.library.shareFbUrl = scope.library.shareUrl +
            '?utm_medium=vf_facebook&utm_source=library_share&utm_content=lid_' + scope.library.id +
            '&kcid=na-vf_facebook-library_share-lid_' + scope.library.id;

          scope.library.shareTwitterUrl = encodeURIComponent(scope.library.shareUrl +
            '?utm_medium=vf_twitter&utm_source=library_share&utm_content=lid_' + scope.library.id +
            '&kcid=na-vf_twitter-library_share-lid_' + scope.library.id);
          scope.library.shareText = 'Discover this amazing @Kifi library about ' + scope.library.name + '!';

          getInvitePromise().then(function (invite) {
            scope.library.invite = invite;
          });

          var image = scope.library.image;
          if (image) {
            scope.coverImageUrl = env.picBase + '/' + image.path;
            scope.coverImagePos = formatCoverImagePos(image);
          }
        }

        function augmentFollower(follower) {
          follower.picUrl = friendService.getPictureUrlForUser(follower);
          follower.profileUrl = routeService.getProfileUrl(follower.username);

          return follower;
        }

        function preloadSocial() {
          if (!$FB.failedToLoad && !$FB.loaded) {
            $FB.init();
          }
          if (!$twitter.failedToLoad && !$twitter.loaded) {
            $twitter.load();
          }
        }
        scope.$evalAsync(preloadSocial);

        function onScroll() {
          scope.$apply(function () {
            scope.pageScrolled = $window.document.body.scrollTop > 0;
          });
        }

        function scrollToTop() {
          $window.document.body.scrollTop = 0;
          scope.pageScrolled = false;
        }

        function positionSearchFollow() {
          $timeout(function () {
            searchFollowElement.css({
              'left': headerLinksElement.offset().left + headerLinksElement.width() + 15 + 'px'
            });
          }, 200);
        }

        function showLibrarySearchBar() {
          if (platformService.isSupportedMobilePlatform()) {
            return;
          }

          scope.librarySearchInProgress = true;

          scrollToTop();

          if (!searchFollowElement.length) {
            searchFollowElement = angular.element('.kf-keep-lib-footer-button-follow-in-search');
          }

          // Reset any previous shifts of the right header.
          headerLinksElement.css({ 'margin-right': headerLinksWidth });

          // Shift header to the left and drop down follow button.
          $timeout(function () {
            if (!headerLinksShifted) {
              headerLinksElement.css({
                'margin-right': '150px'
              });

              headerLinksShifted = true;
            }

            searchFollowElement.css({
              'left': headerLinksElement.offset().left + headerLinksElement.width() + 15 + 'px'
            });

            searchFollowElement.css({
              'transition': 'top 0.5s ease 0.3s',
              'top': '15px'
            });

            libraryBodyElement.css({
              'margin-top': '90px'
            });
          }, 0);
        }

        function hideLibrarySearchBar() {
          scrollToTop();

          scope.librarySearchInProgress = false;
          prevQuery = '';

          if (!searchFollowElement.length) {
            searchFollowElement = angular.element('.kf-keep-lib-footer-button-follow-in-search');
          }

          searchFollowElement.css({
            'transition': 'top 0.2s ease',
            'top': '-100px'
          });

          if (headerLinksShifted) {
            headerLinksElement.css({
              'margin-right': headerLinksWidth
            });

            headerLinksShifted = false;
          }

          libraryBodyElement.css({
            'transition': 'margin-top 0.1s ease',
            'margin-top': '0px'
          });

          $timeout(function () {
            scope.search = { text: '' };
            element.find('.kf-keep-lib-search-input').blur();
          });
        }


        //
        // Scope methods.
        //
        scope.acceptInvitation = function () {
          scope.followLibrary();
        };

        scope.ignoreInvitation = function () {
          if (scope.library.invite) {
            libraryService.declineToJoinLibrary(scope.library.id).then(function () {
              scope.library.invite.actedOn = true;
            })['catch'](modalService.openGenericErrorModal);
          }
        };

        scope.onClickAddCoverImage = function (event) {
          if (event.which === 1) {
            angular.element('.kf-keep-lib-cover-image-file').click();
            libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedAddCoverImage' });
          }
        };

        scope.onCoverImageFileChosen = function (file) {
          scope.$apply(function () {
            if (/^image\/(?:jpeg|png|gif)$/.test(file.type)) {
              coverImageFile = file;
              $timeout(readCoverImageFile);
              libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedCoverImageFile' });
            } else {
              scope.coverImageError = 'Please choose a .jpg, .png or .gif file.';
              $timeout(function () {
                scope.coverImageError = null;
              }, 2400);
            }
          });
        };

        function formatCoverImagePos(pos) {
          return pos ? pos.x + '% ' + pos.y + '%' : '50% 50%';
        }

        function readCoverImageFile() {
          var file = coverImageFile;
          if (URL) {
            useCoverImageFileUrl(URL.createObjectURL(file), true);
          } else {
            var reader = new FileReader();
            reader.onload = function (e) {
              useCoverImageFileUrl(e.target.result);
            };
            reader.readAsDataURL(file);
          }
        }

        function useCoverImageFileUrl(url, isObjectUrl) {
          loadImage(url).then(function (img) {
            var pos = {x: 50, y: 50};
            var posCss = formatCoverImagePos(pos);
            scope.coverImagePreview = true;
            scope.coverImageUrl = url;
            scope.coverImagePos = posCss;
            $timeout(function () {
              var $image = angular.element('.kf-keep-lib-cover-image')
              .data({naturalSize: [img.naturalWidth, img.naturalHeight], objectUrl: isObjectUrl ? url : null})
              .css({
                'background-image': 'url(' + url + ')',
                'background-position': posCss
              });
              enterCoverImagePosMode($image, pos);
            });
          });
        }

        function loadImage(url) {
          var deferred = $q.defer();
          var img = new Image();
          img.onload = function () {
            deferred.resolve(this);
          };
          img.onerror = function (e) {
            deferred.reject(e);
          };
          img.src = url;
          return deferred.promise;
        }

        function enterCoverImagePosMode($image, pos) {
          coverImagePos = pos;
          $image
            .on('mouseover', onCoverImageMouseOver)
            .on('mousedown', onCoverImageMouseDown)
            .triggerHandler('mouseover');
        }

        function leaveCoverImagePosMode($image) {
          $image
            .off('mouseover', onCoverImageMouseOver)
            .off('mousedown', onCoverImageMouseDown)
            .removeClass('kf-ew kf-ns');
          coverImagePos = null;
        }

        function onCoverImageMouseDown(e) {
          if (!e.target.href && e.which === 1) {
            e.preventDefault();
            var x0 = e.screenX;
            var y0 = e.screenY;
            var $image = angular.element(this);
            var data = $image.data();

            var vw = this.offsetWidth, nw = data.naturalSize[0], hScale = vw / nw;
            var vh = this.offsetHeight, nh = data.naturalSize[1], vScale = vh / nh;
            var pctPerPx =
              hScale > vScale ? {x: 0, y: 100 / (vh - nh * hScale)} :
              hScale < vScale ? {x: 100 / (vw - nw * vScale), y: 0} : {x: 0, y: 0};

            data.mousemove = _.throttle(onCoverImageMouseMove.bind(this, data, coverImagePos, x0, y0, pctPerPx), 10);
            data.mouseup = onCoverImageMouseUp.bind(this, data);
            data.mouseout = onCoverImageMouseOut.bind(this, data);
            document.addEventListener('mousemove', data.mousemove, true);
            document.addEventListener('mouseup', data.mouseup, true);
            document.addEventListener('mouseout', data.mouseout, true);
            $image.addClass('kf-dragging');
            var sel = $window.getSelection();
            if (sel && sel.rangeCount) {
              sel.collapseToEnd();
            }
            if (!coverImageMoveTracked) {
              libraryService.trackEvent('user_clicked_page', scope.library, { action: 'positionedCoverImage' });
              coverImageMoveTracked = true;
            }
          }
        }

        function onCoverImageMouseMove(data, pos0, x0, y0, pctPerPx, e) {
          var pos = {
            x: Math.min(100, Math.max(0, pos0.x + pctPerPx.x * (e.screenX - x0))),
            y: Math.min(100, Math.max(0, pos0.y + pctPerPx.y * (e.screenY - y0)))
          };
          coverImagePos = pos;
          this.style.backgroundPosition = formatCoverImagePos(pos);
        }

        function onCoverImageMouseUp(data) {
          document.removeEventListener('mousemove', data.mousemove, true);
          document.removeEventListener('mouseup', data.mouseup, true);
          document.removeEventListener('mouseout', data.mouseout, true);
          delete data.mousemove;
          delete data.mouseup;
          delete data.mouseout;
          angular.element(this).removeClass('kf-dragging');
        }

        function onCoverImageMouseOut(data, e) {
          if (!e.relatedTarget) {
            data.mouseup();
          }
        }

        function onCoverImageMouseOver() {
          var $image = angular.element(this);
          var naturalSize = $image.data('naturalSize');
          var hScale = this.offsetWidth / naturalSize[0];
          var vScale = this.offsetHeight / naturalSize[1];
          $image
            .removeClass('kf-ew kf-ns')
            .addClass(hScale > vScale ? 'kf-ns' : hScale < vScale ? 'kf-ew' : '');
        }

        scope.cancelCoverImageChange = function () {
          scope.coverImagePreview = false;
          var image = scope.library.image;
          var $image = angular.element('.kf-keep-lib-cover-image');
          leaveCoverImagePosMode($image);
          if (coverImageFile) {
            var url = image ? env.picBase + '/' + image.path : null;
            scope.coverImageUrl = url;
            if (url) {
              $image.css({
                'background-image': 'url(' + url + ')',
                'background-position': formatCoverImagePos(image)
              });
            }
            var objectUrl = $image.data('objectUrl');
            if (objectUrl) {
              if (url) {
                URL.revokeObjectURL(objectUrl);
              } else {
                $image.on('transitionend', function f(e) {
                  if (e.target === this && e.originalEvent.propertyName === 'height') {
                    angular.element(this).off(e.type, f);
                    URL.revokeObjectURL(objectUrl);
                  }
                });
              }
            }
          } else {
            $image.css('background-position', formatCoverImagePos(image));
          }
          libraryService.trackEvent('user_clicked_page', scope.library, {
            action: coverImageFile ? 'clickedCancelCoverImage' : 'clickedCancelCoverImageMove'
          });
          coverImageFile = null;
        };

        scope.applyCoverImageChange = function () {
          var $image = angular.element('.kf-keep-lib-cover-image');
          var $shade = $image.find('.kf-keep-lib-cover-image-preview');
          var $progress = angular.element();
          var pos = coverImagePos;
          leaveCoverImagePosMode($image);
          $shade.find('a').removeAttr('href');
          scope.coverImageProgress = true;
          $timeout(function () {
            $progress = $image.find('.kf-keep-lib-cover-image-progress');
          });

          var promise = coverImageFile ?
            uploadCoverImage(coverImageFile, pos)
            .then(function done(image) {
              scope.library.image = image;
              var url = env.picBase + '/' + image.path;
              loadImage(url).then(function () {
                $image.css('background-image', 'url(' + url + ')');
                var objectUrl = $image.data('objectUrl');
                if (objectUrl) {
                  URL.revokeObjectURL(objectUrl);
                }
              });
              $timeout(function () {
                coverImageFile = null;
                scope.coverImageUrl = url;
                scope.coverImagePreview = false;
                scope.coverImageProgress = false;
                fakeHover($image);
              }, 500); // allowing progress bar transition to complete and register in user's mind
            }) :
            fakeProgress(
              $http.post(routeService.positionLibraryCoverImage(scope.library.id), {
                path: scope.library.image.path,
                x: Math.round(pos.x),
                y: Math.round(pos.y)
              }))
            .then(function done() {
              scope.library.image.x = Math.round(pos.x);
              scope.library.image.y = Math.round(pos.y);
              $timeout(function () {
                scope.coverImagePreview = false;
                scope.coverImageProgress = false;
                fakeHover($image);
              }, 500); // allowing progress bar transition to complete and register in user's mind
            });

          promise.then(function done() {
            $progress.addClass('kf-done');
          }, function fail() {
            $progress.on('transitionend', function () {
              scope.coverImageProgress = false;
              $shade.find('a').prop('href', 'javascript:'); // jshint ignore:line
              enterCoverImagePosMode($image, pos);
            }).addClass('kf-fail');
          }, function progress(fraction) {
            $progress.css('width', fraction * 100 + '%');
          });

          libraryService.trackEvent('user_clicked_page', scope.library, {
            action: coverImageFile ? 'clickedApplyCoverImage' : 'clickedApplyCoverImageMove'
          });
        };

        function uploadCoverImage(file, pos) {
          var url = routeService.uploadLibraryCoverImage(scope.library.id, Math.round(pos.x), Math.round(pos.y));
          var xhr = new $window.XMLHttpRequest();
          xhr.withCredentials = true;
          var deferred = $q.defer(), fraction = 0, timeout, tickMs = 200;
          xhr.upload.addEventListener('progress', function (e) {
            if (e.lengthComputable) {
              var frac = e.loaded / e.total / 2;  // halved b/c server processes for ~3s after upload completes
              if (frac > fraction) {
                fraction = frac;
                deferred.notify(fraction);
                $timeout.cancel(timeout);
                timeout = $timeout(tick, tickMs, false);
              }
            }
          });
          xhr.addEventListener('load', function () {
            deferred.resolve(xhr.response);
          });
          xhr.addEventListener('loadend', function () {
            deferred.reject(); // harmless if resolved
            $timeout.cancel(timeout);
          });
          xhr.open('POST', url, true);
          xhr.responseType = 'json';
          xhr.send(file);

          function tick() {
            if (fraction > 0.88) {
              fraction += Math.min(0.005, (1 - fraction) / 2);
            } else {
              fraction += 0.1 * (0.9 - fraction);
            }
            deferred.notify(fraction);

            if (fraction < 0.9999) {
              timeout = $timeout(tick, tickMs, false);
            }
          }
          timeout = $timeout(tick, tickMs, false);  // ensuring progress bar doesn't stall

          return deferred.promise;
        }

        function fakeProgress(req) {
          var deferred = $q.defer(), fraction = 0, timeout, tickMs = 40;
          req.success(function (data) {
            deferred.resolve(data);
          }).error(function (data) {
            deferred.reject(data);
          }).then(function () {
            $timeout.cancel(timeout);
          });

          function tick() {
            if (fraction > 0.88) {
              fraction += Math.min(0.005, (1 - fraction) / 2);
            } else {
              fraction += 0.06 * (0.9 - fraction);
            }
            deferred.notify(fraction);

            if (fraction < 0.9999) {
              timeout = $timeout(tick, tickMs, false);
            }
          }
          timeout = $timeout(tick, 0, false);

          return deferred.promise;
        }

        function fakeHover($image) {
          $image.not(':hover').addClass('kf-fake-hover').one('mouseover', function () {
            $image.removeClass('kf-fake-hover');
          });
        }

        scope.onCoverImageAdjustEngage = function (event) {
          // only honoring clicks not triggered using the mouse (e.g. Enter key) to avoid double-handling mousedown + click
          if (event.which === 1 && !event.isDefaultPrevented() && (event.type !== 'click' || !event.screenY)) {
            event.preventDefault();
            if (!scope.coverImageMenuShowing) {
              angular.element('.kf-keep-lib-cover-image-adjust').addClass('kf-active');
              document.addEventListener('mousedown', onCoverImageAdjustDocMouseDown, true);
              scope.coverImageMenuShowing = true;
            }
          }
        };

        function hideCoverImageMenu() {
          angular.element('.kf-keep-lib-cover-image-adjust').removeClass('kf-active');
          document.removeEventListener('mousedown', onCoverImageAdjustDocMouseDown, true);
          scope.coverImageMenuShowing = false;
        }

        function onCoverImageAdjustDocMouseDown(event) {
          if (!angular.element(event.target).is('.kf-keep-lib-cover-image-menu,.kf-keep-lib-cover-image-menu *')) {
            hideCoverImageMenu();
            event.preventDefault();
          }
        }

        scope.onRemoveCoverImageMouseUp = function (event) {
          if (event.which === 1) {
            hideCoverImageMenu();

            var $image = angular.element('.kf-keep-lib-cover-image');
            var $progress = angular.element();
            scope.coverImageProgress = true;
            $timeout(function () {
              $progress = $image.find('.kf-keep-lib-cover-image-progress');
            });

            fakeProgress($http['delete'](routeService.removeLibraryCoverImage(scope.library.id)))
            .then(function done() {
              $progress.addClass('kf-done');
              $timeout(function () {
                scope.library.image = null;
                scope.coverImageUrl = null;
                scope.coverImagePreview = false;
                scope.coverImageProgress = false;
              }, 500); // allowing progress bar transition to complete and register in user's mind
            }, function fail() {
              $progress.on('transitionend', function () {
                scope.coverImageProgress = false;
              }).addClass('kf-fail');
            }, function progress(fraction) {
              $progress.css('width', fraction * 100 + '%');
            });

            libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedRemoveCoverImage' });
          }
        };

        scope.onChangeCoverImageMouseUp = function (event) {
          if (event.which === 1) {
            $timeout(hideCoverImageMenu);
            angular.element('.kf-keep-lib-cover-image-file').click();
            libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedChangeCoverImage' });
          }
        };

        scope.onMoveCoverImageMouseUp = function (event) {
          if (event.which === 1) {
            hideCoverImageMenu();
            var url = scope.coverImageUrl;
            loadImage(url).then(function (img) {
              var $image = angular.element('.kf-keep-lib-cover-image')
                .data('naturalSize', [img.naturalWidth, img.naturalHeight]);
              scope.coverImagePreview = true;
              enterCoverImagePosMode($image, {x: scope.library.image.x, y: scope.library.image.y});
            });
            libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedMoveCoverImage' });
          }
        };

        scope.showLongDescription = function () {
          scope.clippedDescription = false;
        };

        scope.isUserCreatedLibrary = function () {
          return scope.library.kind === 'user_created';
        };

        scope.isMyLibrary = function () {
          return libraryService.isMyLibrary(scope.library);
        };

        scope.canBeShared = function () {
          // Only user-created (i.e. not Main or Secret) libraries can be shared.
          // Of the user-created libraries, public libraries can be shared by any Kifi user;
          // discoverable/secret libraries can be shared only by the library owner.
          return !scope.isUserLoggedOut && scope.isUserCreatedLibrary() &&
                 (scope.isPublic() || scope.isMyLibrary());
        };

        scope.isPublic = function () {
          return scope.library.visibility === 'published';
        };

        scope.shareFB = function () {
          trackShareEvent('clickedShareFacebook');
          $FB.ui({
            method: 'share',
            href: scope.library.shareFbUrl
          });
        };

        scope.shareTwitter = function () {
          trackShareEvent('clickedShareTwitter');
        };

        // TODO: determine this on the server side in the library response. For now, doing it client side.
        scope.canFollowLibrary = function () {
          return !scope.alreadyFollowingLibrary() && !scope.isMyLibrary();
        };

        scope.alreadyFollowingLibrary = function () {
          return libraryService.isFollowingLibrary(scope.library);
        };

        scope.followLibrary = function () {
          scope.followCallback();
          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedFollowButton' });

          if (platformService.isSupportedMobilePlatform()) {
            var url = $location.absUrl();
            platformService.goToAppOrStore(url + (url.indexOf('?') > 0 ? '&' : '?') + 'follow=true');
            return;
          } else if ($rootScope.userLoggedIn === false) {
            return signupService.register({libraryId: scope.library.id, intent: 'follow'});
          }

          libraryService.joinLibrary(scope.library.id)['catch'](modalService.openGenericErrorModal);
        };

        scope.unfollowLibrary = function () {
          // TODO(yrl): ask Jen about whether we can remove this.
          libraryService.trackEvent('user_clicked_page', scope.library, { action: 'unfollow' });

          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedUnfollowButton' });
          libraryService.leaveLibrary(scope.library.id)['catch'](modalService.openGenericErrorModal);
        };

        scope.followStick = function (isStuck) {
          if (scope.librarySearchInProgress) {
            return;
          }

          if (isStuck) {
            angular.element('html.kf-mobile .kf-header-right').css({'display': 'none'});
            angular.element('.kf-header-right').css({'margin-right': '150px'});
          } else {
            angular.element('html.kf-mobile .kf-header-right').css({'display': ''});
            angular.element('.kf-header-right').css({'margin-right': ''});
          }
        };

        scope.followButtonMaxTop = platformService.isSupportedMobilePlatform() ? 25 : 15;

        scope.manageLibrary = function () {
          modalService.open({
            template: 'libraries/manageLibraryModal.tpl.html',
            modalData: {
              pane: 'manage',
              library: scope.library,
              currentPageOrigin: scope.currentPageOrigin,
              returnAction: function () {
                libraryService.getLibraryById(scope.library.id, true).then(function (data) {
                  return libraryService.getLibraryByUserSlug(scope.username, data.library.slug, authToken, true).then(function (library) {
                    _.assign(scope.library, library);
                    augmentData();
                    adjustFollowerPicsSize();

                    if (data.library.slug !== scope.librarySlug) {
                      $location.path('/' + scope.username + '/' + data.library.slug);
                    }
                  });
                })['catch'](modalService.openGenericErrorModal);
              }
            }
          });
        };

        scope.toggleEditKeeps = function () {
          scope.toggleEdit();
          scope.editKeepsText = scope.editKeepsText === 'Edit Keeps' ? 'Done Editing' : 'Edit Keeps';
        };

        scope.showFollowers = function () {
          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedViewFollowers' });

          if (scope.library.owner.id === profileService.me.id) {
            modalService.open({
              template: 'libraries/manageLibraryModal.tpl.html',
              modalData: {
                pane: 'members',
                library: scope.library,
                currentPageOrigin: scope.currentPageOrigin
              }
            });
          } else {
            modalService.open({
              template: 'libraries/libraryFollowersModal.tpl.html',
              modalData: {
                library: scope.library,
                currentPageOrigin: scope.currentPageOrigin
              }
            });
          }
        };

        scope.onSearchInputFocus = function () {
          // Track click/focus on search bar.
          $rootScope.$emit('trackLibraryEvent', 'click', {
            action: 'clickedSearchBody'
          });

          if (!scope.librarySearchInProgress) {
            showLibrarySearchBar();
          }
        };

        scope.onSearchExit = function () {
          hideLibrarySearchBar();
          $state.go('library.keeps');
        };

        scope.onSearchInputChange = _.debounce(function (query) {
          $timeout(function () {
            if (query) {
              // Replace last history record if we were previously already searching a query.
              var location = prevQuery ? 'replace' : true;

              prevQuery = query;
              $state.go('library.search', { q: query, f: 'a' }, { location: location });
            } else {
              prevQuery = '';
              $timeout(function () {
                scope.search = { 'text': '' };
              });

              // Keep showing the search bar when the user clears the search input.
              keepShowingSearchBar = true;

              $state.go('library.keeps');
            }
          });
        }, 50, {
          leading: true
        });


        //
        // Watches and listeners.
        //

        // Wait until library data is ready before processing information to display the library card.
        scope.$watch('loading', function (newVal) {
          if (!newVal) {
            augmentData();
            adjustFollowerPicsSize();

            $timeout(function () {
              element.addClass('kf-loaded');
              if (scope.librarySearch) {
                showLibrarySearchBar();
              }
            });
          }
        });

        [
          $rootScope.$on('libraryKeepCountChanged', function (e, libraryId, keepCount) {
            if (libraryId === scope.library.id) {
              scope.library.numKeeps = keepCount;
            }
          }),
          $rootScope.$on('libraryJoined', function (e, libraryId) {
            var lib = scope.library;
            if (lib && libraryId === lib.id && lib.access === 'none') {
              (lib.invite || {}).actedOn = true;
              lib.access = 'read_only';
              lib.numFollowers++;
              var me = profileService.me;
              if (!_.contains(lib.followers, {id: me.id})) {
                lib.followers.push(augmentFollower(_.pick(me, 'id', 'firstName', 'lastName', 'pictureName', 'username')));
                adjustFollowerPicsSize();
              }
            }
          }),
          $rootScope.$on('libraryLeft', function (e, libraryId) {
            var lib = scope.library;
            if (lib && libraryId === lib.id && lib.access !== 'none') {
              lib.access = 'none';
              lib.numFollowers--;
              if (_.remove(lib.followers, {id: profileService.me.id}).length > 0) {
                adjustFollowerPicsSize();
              }
            }
          })
        ].forEach(function (deregister) {
          scope.$on('$destroy', deregister);
        });

        // Update the search bar and search input text to be consistent with the current state.
        if (scope.isUserLoggedOut) {
          var deregisterUpdateSearchBarAndInput = $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams) {
            if (toState.name === 'library.search') {
              showLibrarySearchBar();
              scope.search = { 'text': toParams.q };
            }

            if (toState.name === 'library.keeps') {
              prevQuery = '';

              if (keepShowingSearchBar) {
                keepShowingSearchBar = false;
              } else {
                hideLibrarySearchBar();
                scope.search = { 'text': '' };
              }
            }
          });
          scope.$on('$destroy', deregisterUpdateSearchBarAndInput);
        }


        // Update how many follower pics are shown when the window is resized.
        var adjustFollowerPicsSizeOnResize = _.debounce(adjustFollowerPicsSize, 200);
        $window.addEventListener('resize', adjustFollowerPicsSizeOnResize);
        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', adjustFollowerPicsSizeOnResize);
        });

        // Update follower button in search when the window is resized.
        var positionSearchFollowOnResize = _.debounce(positionSearchFollow, 100);
        $window.addEventListener('resize', positionSearchFollowOnResize);
        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', positionSearchFollowOnResize);
        });

        $window.addEventListener('scroll', onScroll);
        scope.$on('$destroy', function () {
          $window.removeEventListener('scroll', onScroll);
        });


        scope.currentPageOrigin = scope.recommendation ? 'recommendationsPage' : 'libraryPage';
      }
    };
  }
]);
