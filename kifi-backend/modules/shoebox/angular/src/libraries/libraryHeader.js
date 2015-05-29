'use strict';

angular.module('kifi')

.directive('kfLibraryHeader', [
  '$http', '$location', '$q', '$rootScope', '$state', '$stateParams', '$timeout', '$window', '$$rAF',
  '$filter', 'env', 'libraryService', 'modalService','profileService', 'platformService', 'signupService',
  'routeService', 'linkify',
  function ($http, $location, $q, $rootScope, $state, $stateParams, $timeout, $window, $$rAF,
            $filter, env, libraryService, modalService, profileService, platformService, signupService,
            routeService, linkify) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        username: '=',
        librarySlug: '=',
        imageLoaded: '=',
        editMode: '=',
        librarySearch: '=',
        followCallback: '&',
        clickLibraryCallback: '&'
      },
      templateUrl: 'libraries/libraryHeader.tpl.html',
      link: function (scope, element) {

        //
        // Internal data.
        //
        var authToken = $location.search().authToken || '';
        var coverImageFile;
        var coverImageMoveTracked;
        var URL = $window.URL || $window.webkitURL;
        var descWrapEl = element.find('.kf-lh-desc-wrap');
        var descEl = descWrapEl.find('.kf-lh-desc');

        //
        // Scope data.
        //
        scope.Math = Math;
        scope.search = { 'text': $stateParams.q || '' };
        scope.isMobile = platformService.isSupportedMobilePlatform();
        scope.descExpanded = false;
        scope.descScrollable = false;
        scope.imagePreview = null;
        scope.followBtnJustClicked = false;
        scope.onCollabExperiment = (profileService.me.experiments || []).indexOf('collaborative') > -1;
        scope.collabsCanInvite = false;

        //
        // Internal methods.
        //

        function augmentData() {
          var lib = scope.library;
          lib.descriptionHtml = linkify(lib.description || '').replace(/\n+/g, '<br>');
          lib.absUrl = env.origin + lib.url;
          lib.isSystem = lib.kind.lastIndexOf('system_', 0) === 0;
          scope.collabsCanInvite = lib.whoCanInvite === 'collaborator';

          $timeout(function () {
            var lh = parseFloat(descWrapEl.css('line-height'), 10);
            scope.descFits = descEl[0].scrollHeight <= Math.ceil(3 * lh);
          });
        }

        function setMembersToShow(members, numMembers, numMembersFit) {
          var showPlusMembers = Math.min(members.length, numMembersFit) < numMembers;
          var numMembersToShow = Math.min(members.length, numMembersFit);
          var membersToShow = members.slice(0, numMembersToShow);
          var numMoreMembersText = showPlusMembers ? $filter('num')(numMembers - numMembersToShow) : '';
          return [membersToShow, numMoreMembersText];
        }

        //
        // Scope methods.
        //
        scope.acceptInvitation = function () {
          scope.followLibrary();
        };

        scope.ignoreInvitation = function () {
          if (scope.library && scope.library.invite) {
            libraryService.declineToJoinLibrary(scope.library.id).then(function() {
              scope.library.invite = null;
            });
          }
        };

        scope.changeSubscription = function () {
          var mem = scope.library.membership;
          if (mem) {
            libraryService.updateSubscriptionToLibrary(scope.library.id, !mem.subscribed).then(function() {
              mem.subscribed = !mem.subscribed;
            })['catch'](modalService.openGenericErrorModal);
          } else {
            scope.followLibrary({subscribed: true});
          }
        };

        scope.onAddCoverImageMouseUp = function (event) {
          if (event.which === 1) {
            angular.element('.kf-lh-cover-file').click();
            libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedAddCoverImage' });
          }
        };

        scope.onCoverImageFileChosen = function (files) {
          var file = files[0];
          if (/^image\/(?:jpeg|png|gif)$/.test(file.type)) {
            coverImageFile = file;
            $timeout(readCoverImageFile);
            libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedCoverImageFile' });
          } else {
            modalService.openGenericErrorModal({
              modalData: {
                genericErrorMessage: 'Please choose a .jpg, .png or .gif file.'
              }
            });
          }
        };

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
            var el = element.find('.kf-lh-cover')[0];
            scope.imagePreview = {
              url: url,
              isObjectUrl: isObjectUrl,
              natural: {w: img.naturalWidth, h: img.naturalHeight},
              w: el.clientWidth,
              h: el.clientHeight,
              x: 50, y: 50
            };
          });
        }

        function revokeImagePreviewObjectUrlWhenDone() {
          if (scope.imagePreview.isObjectUrl) {
            var revoke = _.once(angular.bind(URL, URL.revokeObjectURL, scope.imagePreview.url));
            element.find('.kf-lh-cover-preview').on('transitionend', revoke);
            $timeout(revoke, 1000);
          }
        }

        function loadImage(url) {
          var deferred = $q.defer();
          var img = new Image();
          img.onload = function () {
            scope.$apply(function () {
              deferred.resolve(img);
            });
          };
          img.onerror = function (e) {
            scope.$apply(function () {
              deferred.reject(e);
            });
          };
          img.src = url;
          return deferred.promise;
        }

        scope.onImagePreviewMouseOver = function (event) {
          scope.imagePreview.w = event.target.offsetWidth;
          scope.imagePreview.h = event.target.offsetHeight;
        };

        scope.onImagePreviewMouseDown = function (e) {
          if (e.which === 1 && e.target.tagName !== 'button' && scope.imagePreview.progress === undefined) {
            e.preventDefault();
            var x0 = e.screenX;
            var y0 = e.screenY;

            var vw = scope.imagePreview.w, nw = scope.imagePreview.natural.w, hScale = vw / nw;
            var vh = scope.imagePreview.h, nh = scope.imagePreview.natural.h, vScale = vh / nh;
            var pctPerPx =
              hScale > vScale ? {x: 0, y: 100 / (vh - nh * hScale)} :
              hScale < vScale ? {x: 100 / (vw - nw * vScale), y: 0} : {x: 0, y: 0};

            var el = e.currentTarget;
            var previewEl = element.find('.kf-lh-cover-preview')[0];
            var handlers = scope.imagePreview.handlers = {
              mousemove: _.throttle(onImagePreviewDocMouseMove.bind(el, previewEl, _.pick(scope.imagePreview, 'x', 'y'), x0, y0, pctPerPx), 10),
              mouseup: onImagePreviewDocMouseUp.bind(el),
              mouseout: onImagePreviewDocMouseOut.bind(el)
            };
            document.addEventListener('mousemove', handlers.mousemove, true);
            document.addEventListener('mouseup', handlers.mouseup, true);
            document.addEventListener('mouseout', handlers.mouseout, true);
            angular.element(el).addClass('kf-dragging');
            var sel = $window.getSelection();
            if (sel && sel.rangeCount) {
              sel.collapseToEnd();
            }
            if (!coverImageMoveTracked) {
              libraryService.trackEvent('user_clicked_page', scope.library, { action: 'positionedCoverImage' });
              coverImageMoveTracked = true;
            }
          }
        };

        function onImagePreviewDocMouseMove(el, pos0, x0, y0, pctPerPx, e) {
          var x = Math.min(100, Math.max(0, pos0.x + pctPerPx.x * (e.screenX - x0)));
          var y = Math.min(100, Math.max(0, pos0.y + pctPerPx.y * (e.screenY - y0)));
          if (x !== scope.imagePreview.x || y !== scope.imagePreview.y) {
            scope.imagePreview.x = x;
            scope.imagePreview.y = y;
            el.style.backgroundPosition = x + '% ' + y + '%';  // avoiding scope.$apply for responsiveness
          }
        }

        function onImagePreviewDocMouseUp() {
          var handlers = scope.imagePreview.handlers;
          document.removeEventListener('mousemove', handlers.mousemove, true);
          document.removeEventListener('mouseup', handlers.mouseup, true);
          document.removeEventListener('mouseout', handlers.mouseout, true);
          delete handlers.mousemove;
          delete handlers.mouseup;
          delete handlers.mouseout;
          angular.element(this).removeClass('kf-dragging');
        }

        function onImagePreviewDocMouseOut(e) {
          if (!e.relatedTarget) {
            scope.imagePreview.handlers.mouseup();
          }
        }

        scope.cancelCoverImageChange = function () {
          libraryService.trackEvent('user_clicked_page', scope.library, {
            action: coverImageFile ? 'clickedCancelCoverImage' : 'clickedCancelCoverImageMove'
          });
          revokeImagePreviewObjectUrlWhenDone();
          scope.imagePreview = coverImageFile = null;
        };

        scope.applyCoverImageChange = function () {
          var preview = scope.imagePreview;
          var pos = {
            x: Math.round(preview.x),
            y: Math.round(preview.y)
          };

          // In Angular 1.3, use ng-attr-disabled="{{disabled || undefined}}".
          var buttons = element.find('.kf-lh-cover-apply,.kf-lh-cover-cancel').prop('disabled', true);
          preview.progress = 0;

          var promise = coverImageFile ?
            uploadCoverImage(coverImageFile, pos)
            .then(function done(image) {  // timeout ensures progress bar can transition to complete and register in user's mind
              $q.all([loadImage(env.picBase + '/' + image.path), $timeout(angular.noop, 500)]).then(function () {
                scope.library.image = image;
                scope.imageLoaded = true;
                revokeImagePreviewObjectUrlWhenDone();
                scope.imagePreview = coverImageFile = null;
                scope.settingImage = true;
                $timeout(function () {
                  scope.settingImage = false;
                });
              });
            }) :
            fakeProgress(
              $http.post(routeService.positionLibraryCoverImage(scope.library.id), {
                path: scope.library.image.path,
                x: pos.x,
                y: pos.y
              }))
            .then(function done() {
              scope.library.image.x = pos.x;
              scope.library.image.y = pos.y;
              $timeout(function () {
                scope.imagePreview = null;
              }, 500); // allowing progress bar transition to complete and register in user's mind
            });

          promise.then(function done() {
            preview.progress = 100;
          }, function fail() {
            preview.progress = 0;
            $timeout(function () {
              delete preview.progress;
              buttons.prop('disabled', false);
            }, 500); // allowing progress bar transition to complete and register in user's mind
          }, function progress(fraction) {
            preview.progress = fraction * 100;
          });

          libraryService.trackEvent('user_clicked_page', scope.library, {
            action: coverImageFile ? 'clickedApplyCoverImage' : 'clickedApplyCoverImageMove'
          });
        };

        function uploadCoverImage(file, pos) {
          var url = routeService.uploadLibraryCoverImage(scope.library.id, pos.x, pos.y);
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

        scope.onRemoveCoverImageMouseUp = function (event) {
          if (event.which === 1) {
            $http['delete'](routeService.removeLibraryCoverImage(scope.library.id)).then(function done() {
              scope.library.image = null;
            }, function fail() {
              modalService.openGenericErrorModal();
            });

            libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedRemoveCoverImage' });
          }
        };

        scope.onChangeCoverImageMouseUp = function (event) {
          if (event.which === 1) {
            libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedChangeCoverImage' });
          }
        };

        scope.onMoveCoverImageMouseUp = function (event) {
          if (event.which === 1) {
            var url = env.picBase + '/' + scope.library.image.path;
            loadImage(url).then(function (img) {
              var el = element.find('.kf-lh-cover')[0];
              scope.imagePreview = {
                url: url,
                natural: {w: img.naturalWidth, h: img.naturalHeight},
                w: el.offsetWidth,
                h: el.offsetHeight,
                x: scope.library.image.x,
                y: scope.library.image.y
              };
            });
            libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedMoveCoverImage' });
          }
        };

        scope.expandDescription = function () {
          if ('webkitLineClamp' in descEl[0].style) {
            descEl[0].style.display = 'block';
          }
          var height = descEl[0].offsetHeight;
          // TODO: switch to $animate after 1.3 upgrade (takes CSS props to set)
          // $animate.addClass(descWrapEl[0], 'kf-expanded', {height: height});
          scope.descExpanded = true;
          var ms = Math.max(300, Math.min(600, Math.round(100 * Math.log(height - descWrapEl[0].clientHeight))));
          descWrapEl.addClass('kf-expanded-add').css({
            'transition-duration': ms + 'ms,' + (0.6 * ms) + 'ms',
            'transition-delay': '0s'
          });
          descWrapEl.addClass('kf-expanded-add-active').css({'height': height, 'background-color': scope.library.color});
          $timeout(function () {
            descWrapEl.addClass('kf-expanded').removeClass('kf-expanded-add kf-expanded-add-active');
            scope.descScrollable = descEl[0].scrollHeight > descEl[0].clientHeight;
          }, ms);
        };

        scope.collapseDescription = function () {
          scope.descExpanded = false;
          scope.descScrollable = false;

          var collapse = function () {
            // TODO: switch to $animate after 1.3 upgrade (takes CSS props to set)
            // $animate.removeClass(descWrapEl[0], 'kf-expanded', {height: ''});
            var ms = parseDurationMs(descWrapEl);
            descWrapEl.addClass('kf-expanded-remove').css({
              'transition-duration': ms + 'ms,' + (0.8 * ms) + 'ms',
              'transition-delay': '0s,' + (0.2 * ms) + 'ms'
            });
            descWrapEl.addClass('kf-expanded-remove-active').css({'height': '', 'background-color': ''});
            $timeout(function () {
              descWrapEl.removeClass('kf-expanded-remove-active kf-expanded-remove kf-expanded');
              if ('webkitLineClamp' in descEl[0].style) {
                descEl[0].style.display = '';
              }
            }, ms);
          };

          var scrollTop = descEl[0].scrollTop;
          if (scrollTop > 0) {
            smoothScroll(descEl[0], scrollTop, 0, Math.max(100, Math.min(500, 100 * Math.log(scrollTop))))
            .then(angular.noop, angular.noop, function progress(frac) {
              if (collapse && frac >= 0.5) {
                collapse();
                collapse = null;
              }
            });
          } else {
            collapse();
          }
        };

        function parseDurationMs(el) {
          return Math.max.apply(Math, el.css('transition-duration').split(/,/).map(function (dur) {
            var n = parseFloat(dur, 10);
            return dur.replace(/[^a-z]/g, '') === 's' ? n * 1000 : n;
          }));
        }

        function smoothScroll(el, top0, topN, ms) {
          var deferred = $q.defer();
          var t0, ms_1 = 1 / ms, px = topN - top0;
          function step(t) {
            if (!t0) {
              t0 = t;
            }
            var alpha = easeInCubic(Math.min(1, (t - t0) * ms_1));
            var top = el.scrollTop = top0 + Math.round(px * alpha);
            deferred.notify(alpha);
            if (top !== topN) {
              $$rAF(step);
            } else {
              deferred.resolve();
            }
          }
          $$rAF(step);
          return deferred.promise;
        }

        function easeInCubic(t) {
          return t*t*t;
        }

        scope.isFollowing = function () {
          return scope.library.membership && scope.library.membership.access === 'read_only';
        };

        scope.isCollaborating = function () {
          return scope.library.membership && scope.library.membership.access === 'read_write';
        };

        scope.isMember = function () {
          return Boolean(scope.library.membership);
        };

        scope.isOwner = function () {
          return scope.library.membership && scope.library.membership.access === 'owner';
        };

        scope.followLibrary = function (opts) {
          scope.followCallback();
          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedFollowButton' });

          if (scope.isMobile) {
            var url = $location.absUrl();
            platformService.goToAppOrStore(url + (url.indexOf('?') > 0 ? '&' : '?') + 'follow=true');
            return;
          } else if (!$rootScope.userLoggedIn) {
            return signupService.register({libraryId: scope.library.id, intent: 'follow'});
          }

          scope.followBtnJustClicked = (opts && opts.via) === 'followBtn';
          libraryService.joinLibrary(scope.library.id, authToken, opts && opts.subscribed)['catch'](modalService.openGenericErrorModal);
        };

        scope.unfollowLibrary = function () {
          // TODO(yrl): ask Jen about whether we can remove this.
          libraryService.trackEvent('user_clicked_page', scope.library, { action: 'unfollow' });

          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedUnfollowButton' });
          libraryService.leaveLibrary(scope.library.id)['catch'](modalService.openGenericErrorModal);
        };

        scope.manageLibrary = function () {
          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedManageLibrary' });
          modalService.open({
            template: 'libraries/manageLibraryModal.tpl.html',
            modalData: {
              pane: 'manage',
              library: scope.library,
              currentPageOrigin: 'libraryPage',
              returnAction: function () {
                libraryService.getLibraryById(scope.library.id, true).then(function (data) {
                  return libraryService.getLibraryByUserSlug(scope.username, data.library.slug, authToken, true).then(function (library) {
                    _.assign(scope.library, library);
                    augmentData();

                    if (data.library.slug !== scope.librarySlug) {
                      $location.url('/' + scope.username + '/' + data.library.slug);
                    }
                  });
                })['catch'](modalService.openGenericErrorModal);
              }
            }
          });
        };

        scope.toggleEditKeeps = function () {
          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedEditKeeps' });
          scope.editMode = !scope.editMode;
        };

        scope.showFollowers = function () {
          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedViewFollowers' });

          if (scope.onCollabExperiment) {
            scope.openMembersModal('followers_only');
          } else if (scope.library.owner.id === profileService.me.id) {
            $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedManageLibrary' });
            modalService.open({
              template: 'libraries/manageLibraryModal.tpl.html',
              modalData: {
                pane: 'members',
                library: scope.library,
                currentPageOrigin: 'libraryPage'
              }
            });
          } else {
            if (scope.isMobile) {
              return;
            }

            modalService.open({
              template: 'libraries/libraryFollowersModal.tpl.html',
              modalData: {
                library: scope.library,
                currentPageOrigin: 'libraryPage'
              }
            });
          }
        };

        scope.trackTwitterProfile = function () {
          libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedTwitterProfileURL' });
        };

        scope.openMembersModal = function (filterType) {
          modalService.open({
              template: 'libraries/libraryMembersModal.tpl.html',
              modalData: {
                library: scope.library,
                canManageMembers: (scope.isOwner() || (scope.isCollaborating() && scope.collabsCanInvite)),
                amOwner: scope.isOwner(),
                filterType: filterType,
                currentPageOrigin: 'libraryPage'
              }
            });
        };

        scope.openInviteModal = function (inviteType) {
          modalService.open({
            template: 'libraries/libraryInviteSearchModal.tpl.html',
            modalData: {
              library: scope.library,
              inviteType: inviteType,
              currentPageOrigin: 'libraryPage'
            }
          });
        };


        //
        // Watches and listeners.
        //

        scope.$watch('library.numFollowers', function() {
          var numFollowersToFit = scope.onCollabExperiment ? 3 : 5;
          var res = setMembersToShow(scope.library.followers, scope.library.numFollowers, numFollowersToFit);
          scope.followersToShow = res[0];
          scope.numMoreFollowersText = res[1];
        });

        scope.$watch('library.numCollaborators', function() {
          // keep at most 5 circles at all times
          // (1 circle is owner, 1 circle is for adding collab, 1 circle is for additional collabs),
          // but if there are 1 owner + 4 collaborators, then show those 5
          var addCollabButton = scope.isOwner() || (scope.isCollaborating() && scope.collabsCanInvite);
          var numCollabsToFit = addCollabButton && scope.library.numCollaborators > 2 ? 2 : !addCollabButton && scope.library.numCollaborators === 4 ? 4 : 3;
          var res = setMembersToShow(scope.library.collaborators, scope.library.numCollaborators, numCollabsToFit);
          scope.collaboratorsToShow = res[0];
          scope.numMoreCollaboratorsText = res[1];
        });

        [
          $rootScope.$on('libraryKeepCountChanged', function (e, libraryId, keepCount) {
            if (libraryId === scope.library.id) {
              scope.library.numKeeps = keepCount;
            }
          }),
          $rootScope.$on('libraryJoined', function (e, libraryId, membership) {
            if (libraryId === scope.library.id) {
              scope.library.membership = membership;
              scope.library.invite = null;
              var me = profileService.me;
              if (membership.access === 'read_only') {
                scope.library.numFollowers++;
                if (!_.contains(scope.library.followers, {id: me.id})) {
                  scope.library.followers.push(_.pick(me, 'id', 'firstName', 'lastName', 'pictureName', 'username'));
                }
              } else if (membership.access === 'read_write') {
                scope.library.numCollaborators++;
                if (!_.contains(scope.library.collaborators, {id: me.id})) {
                  scope.library.collaborators.push(_.pick(me, 'id', 'firstName', 'lastName', 'pictureName', 'username'));
                }
              }
            }
          }),

          $rootScope.$on('libraryLeft', function (e, libraryId) {
            var lib = scope.library;
            if (lib && libraryId === lib.id && lib.membership) {
              if (lib.membership.access === 'read_only') {
                lib.numFollowers--;
                _.remove(lib.followers, {id: profileService.me.id});
              } else if (lib.membership.access === 'read_write') {
                lib.numCollaborators--;
                _.remove(lib.collaborators, {id: profileService.me.id});
              }
              lib.membership = undefined;
            }
          })
        ].forEach(function (deregister) {
          scope.$on('$destroy', deregister);
        });


        //
        // Initialize.
        //

        augmentData();
      }
    };
  }
]);
