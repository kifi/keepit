'use strict';

angular.module('kifi')

.directive('kfLibraryCard', [
  '$FB', '$location', '$q', '$rootScope', '$window', 'env', 'friendService', 'libraryService', 'modalService',
  'profileService', 'platformService', 'signupService', '$twitter', '$timeout', '$routeParams', '$route',
  'locationNoReload', 'util',
  function ($FB, $location, $q, $rootScope, $window, env, friendService, libraryService, modalService,
      profileService, platformService, signupService, $twitter, $timeout, $routeParams, $route,
      locationNoReload, util) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        username: '=',
        librarySlug: '=',
        recommendation: '=',
        loading: '=',
        toggleEdit: '='
      },
      templateUrl: 'libraries/libraryCard.tpl.html',
      link: function (scope, element/*, attrs*/) {
        //
        // Internal data.
        //
        var uriRe = /(?:\b|^)((?:(?:(https?|ftp):\/\/|www\d{0,3}[.])?(?:[a-z0-9](?:[-a-z0-9]*[a-z0-9])?\.)+(?:com|edu|biz|gov|in(?:t|fo)|mil|net|org|name|coop|aero|museum|a(?:c|d|e|g|i|l|m|o|q|r|s|t|u|z)|b(?:a|b|e|f|g|h|i|m|n|r|t|y|z)|c(?:a|c|d|f|g|h|i|k|l|m|n|o|r|u|x|y|z)|d(?:e|j|k|o)|e(?:c|e|g|s|t)|f(?:i|j|k|m|o|r)|g(?:b|e|f|g|h|i|l|m|n|p|q|r|s|t|u)|h(?:k|m|n|r|u)|i(?:d|e|l|m|n|o|r|s|t)|j(?:e|o|p)|k(?:e|g|h|r|w|y|z)|l(?:b|c|i|k|t|u|v|y)|m(?:c|d|g|h|k|m|n|o|q|r|s|t|u|w|x|y|z)|n(?:a|c|f|i|l|o|u|z)|om|p(?:a|e|g|h|k|l|m|n|r|t|y)|qa|r(?:e|o|u|w)|s(?:a|b|c|d|e|g|h|i|k|m|n|o|t|u|v|z)|t(?:c|d|f|h|j|m|n|o|p|r|t|v|w|z)|u(?:a|g|k|m|s|y|z)|v(?:e|g|i|n|u)|wf|y(?:t|u)|z(?:a|m|r|w)\b))(?::[0-9]{1,5})?(?:\/(?:[^\s()<>]*[^\s`!\[\]{};:.'",<>?«»()“”‘’]|\((?:[^\s()<>]+|(?:\([^\s()<>]+\)))*\))*|\b))(?=[\s`!()\[\]{};:.'",<>?«»“”‘’]|$)/;  // jshint ignore:line
        var authToken = $location.search().authToken || '';
        var prevQuery = '';


        //
        // Scope data.
        //
        scope.isUserLoggedOut = $rootScope.userLoggedIn === false;
        scope.clippedDescription = false;
        scope.followersToShow = 0;
        scope.numAdditionalFollowers = 0;
        scope.editKeepsText = 'Edit Keeps';
        scope.search = { 'text': $routeParams.q || '' };

        var magicImages = {
          'l7SZ3gr3kUQJ': '//djty7jcqog9qu.cloudfront.net/special-libs/l7SZ3gr3kUQJ.png',
          'l4APrlM5wzaM': '//djty7jcqog9qu.cloudfront.net/special-libs/l4APrlM5wzaM.png',
          'l2iJXRO7vtoa': '//djty7jcqog9qu.cloudfront.net/special-libs/l2iJXRO7vtoa.png',
          'l292wb07mhuB': '//djty7jcqog9qu.cloudfront.net/special-libs/l292wb07mhuB.png',
          'lGcw3PhnD9Wo': '//djty7jcqog9qu.cloudfront.net/special-libs/lGcw3PhnD9Wo.png',
          'l3ai2ejn5t9L': '//djty7jcqog9qu.cloudfront.net/special-libs/l3ai2ejn5t9L.png',
          'lzgAqPcczp5J': '//djty7jcqog9qu.cloudfront.net/special-libs/lzgAqPcczp5J.png',
          'l14bTasWaiYK': '//djty7jcqog9qu.cloudfront.net/special-libs/l14bTasWaiYK.png',
          'l5ooCseWZXla': '//djty7jcqog9qu.cloudfront.net/special-libs/l5ooCseWZXla.png',
          'lFiSQapwp732': '//djty7jcqog9qu.cloudfront.net/special-libs/lFiSQapwp732.png',
          'lGWrqQb9JsbJ': '//djty7jcqog9qu.cloudfront.net/special-libs/lGWrqQb9JsbJ.png',
          'lCaeGbBOh5YT': '//djty7jcqog9qu.cloudfront.net/special-libs/lCaeGbBOh5YT.png',
          'lEc2xD0eNU9f': '//djty7jcqog9qu.cloudfront.net/special-libs/lEc2xD0eNU9f.png',
          'l8SVuYHq9Qo5': '//djty7jcqog9qu.cloudfront.net/special-libs/l8SVuYHq9Qo5.jpg',
          'l5jqAsWp5j8Y': '//djty7jcqog9qu.cloudfront.net/special-libs/l5jqAsWp5j8Y.jpg',
          'l8zOB62bja1e': '//djty7jcqog9qu.cloudfront.net/special-libs/l8zOB62bja1e.jpg',
          'l0XQspLziYol': '//djty7jcqog9qu.cloudfront.net/special-libs/l0XQspLziYol.jpg'
        };
        scope.magicImage = magicImages[scope.library.id];


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

        function trackShareEvent(medium) {
          $timeout(function () {
            var attrs = { action: 'shareLibrary', subAction: medium };
            var eventName = (scope.isUserLoggedOut ? 'visitor' : 'user') + '_clicked_page';
            libraryService.trackEvent(eventName, scope.library, attrs);
          });
        }


        function processUrls(text) {
          var parts = text.split(uriRe);

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
          }

          scope.library.followers = scope.library.followers || [];
          scope.library.followers.forEach(function (follower) {
            follower.picUrl = friendService.getPictureUrlForUser(follower);
          });

          var maxLength = 150;
          if (scope.library.description && scope.library.description.length > maxLength && !scope.isUserLoggedOut) {
            // Try to chop off at a word boundary, using a simple space as the word boundary delimiter.
            var clipLastIndex = maxLength;
            var lastSpaceIndex = scope.library.description.lastIndexOf(' ', maxLength);
            if (lastSpaceIndex !== -1) {
              clipLastIndex = lastSpaceIndex + 1;  // Grab the space too.
            }

            scope.library.shortDescription = processUrls(scope.library.description.substr(0, clipLastIndex));
            scope.clippedDescription = true;
          }
          scope.library.formattedDescription = '<p>' + processUrls(scope.library.description).replace(/\n+/, '<p>');

          scope.library.shareUrl = env.origin + scope.library.url;
          scope.library.shareFbUrl = scope.library.shareUrl +
            '?utm_medium=vf_facebook&utm_source=library_invite&utm_content=lid_' + scope.library.id +
            '&kcid=na-vf_facebook-library_invite-lid_' + scope.library.id;

          scope.library.shareTwitterUrl = encodeURIComponent(scope.library.shareUrl +
            '?utm_medium=vf_twitter&utm_source=library_invite&utm_content=lid_' + scope.library.id +
            '&kcid=na-vf_twitter-library_invite-lid_' + scope.library.id);
          scope.library.shareText = 'Discover this amazing @Kifi library about ' + scope.library.name + '!';

          // Figure out whether this library is a library that the user has been invited to.
          // If so, display an invite header.
          var promise = null;
          if (libraryService.invitedSummaries.length) {
            promise = $q.when(libraryService.invitedSummaries);
          } else {
            promise = libraryService.fetchLibrarySummaries(true).then(function () {
              return libraryService.invitedSummaries;
            });
          }

          promise.then(function (invitedSummaries) {
            var maybeLib = _.find(invitedSummaries, { 'id' : scope.library.id });
            if (maybeLib) {
              scope.library.invite = {
                inviterName: maybeLib.inviter.firstName + ' ' + maybeLib.inviter.lastName,
                actedOn: false
              };
            }
          });

          if (scope.$root.userLoggedIn === false) {
            scope.$evalAsync(function () {
              angular.element('.white-background').height(element.height() + 20);
            });
          }
        }

        function preloadSocial () {
          if (!$FB.failedToLoad && !$FB.loaded) {
            $FB.init();
          }
          if (!$twitter.failedToLoad && !$twitter.loaded) {
            $twitter.load();
          }
        }
        scope.$evalAsync(preloadSocial);


        //
        // Scope methods.
        //
        scope.acceptInvitation = function (library) {
          scope.followLibrary(library);
        };

        scope.ignoreInvitation = function (library) {
          if (library.invite) {
            libraryService.declineToJoinLibrary(library.id).then(function () {
              library.invite.actedOn = true;
            })['catch'](modalService.openGenericErrorModal);
          }
        };

        scope.showLongDescription = function () {
          scope.clippedDescription = false;
        };

        scope.isUserLibrary = function (library) {
          return library.kind === 'user_created';
        };

        scope.isMyLibrary = function (library) {
          return library.owner && library.owner.id === profileService.me.id;
        };

        scope.canBeShared = function (library) {
          // Only user created (i.e. not Main or Secret) libraries can be shared.
          // Of the user created libraries, public libraries can be shared by any Kifi user;
          // discoverable/secret libraries can be shared only by the library owner.
          return !scope.isUserLoggedOut && scope.isUserLibrary(library) &&
                 (library.visibility === 'published' ||
                  scope.isMyLibrary(library));
        };

        scope.isPublic = function (library) {
          return library.visibility === 'published';
        };

        scope.shareFB = function () {
          trackShareEvent('facebook');
          $FB.ui({
            method: 'share',
            href: scope.library.shareFbUrl
          });
        };

        scope.shareTwitter = function () {
          trackShareEvent('twitter');
        };

        // TODO: determine this on the server side in the library response. For now, doing it client side.
        scope.canFollowLibrary = function (library) {
          return !scope.alreadyFollowingLibrary(library) && !scope.isMyLibrary(library);
        };

        scope.alreadyFollowingLibrary = function (library) {
          return (library.access && (library.access === 'read_only')) ||
            (_.some(libraryService.librarySummaries, { id: library.id }) && !scope.isMyLibrary(library));
        };

        scope.followLibrary = function (library) {
          if (platformService.isSupportedMobilePlatform()) {
            var url = $location.absUrl();
            if (url.indexOf('?') !== -1) {
              url = url + '&follow=true';
            } else {
              url = url + '?follow=true';
            }
            platformService.goToAppOrStore(url);
            return;
          } else if ($rootScope.userLoggedIn === false) {
            libraryService.trackEvent('visitor_clicked_page', library, { action: 'followButton' });
            return signupService.register({libraryId: scope.library.id});
          }

          libraryService.trackEvent('user_clicked_page', library, { action: 'followed' });

          libraryService.joinLibrary(library.id).then(function (result) {
            if (library.invite) {
              library.invite.actedOn = true;
            }

            if (result === 'already_joined') {
              modalService.openGenericErrorModal({
                modalData: {
                  genericErrorMessage: 'You are already following this library!'
                }
              });
              return;
            }

            library.followers.push({
              id: profileService.me.id,
              firstName: profileService.me.firstName,
              lastName: profileService.me.lastName,
              pictureName: profileService.me.pictureName
            });

            augmentData();
            adjustFollowerPicsSize();
          })['catch'](modalService.openGenericErrorModal);
        };

        scope.unfollowLibrary = function (library) {
          libraryService.trackEvent('user_clicked_page', library, { action: 'unfollow' });
          libraryService.leaveLibrary(library.id)['catch'](modalService.openGenericErrorModal);
        };

        scope.followStick = function (isStuck) {
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
          if (scope.library.owner.id === profileService.me.id) {
            modalService.open({
              template: 'libraries/manageLibraryModal.tpl.html',
              modalData: {
                pane: 'members',
                library: scope.library
              }
            });
          } else {
            modalService.open({
              template: 'libraries/libraryFollowersModal.tpl.html',
              modalData: {
                library: scope.library
              }
            });
          }
        };

        scope.onSearchInputChange = _.debounce(function (query) {
          $timeout(function () {
            if (query) {
              if (prevQuery) {
                locationNoReload.skipReload().search('q', query).replace();

                // When we search using the input inside the library card header, we don't
                // want to reload the page. One consequence of this is that we need to kick
                // SearchController to initialize a search when the search query changes if
                // we initially started with a url that is a library url that has no search
                // parameters.
                if (!$route.current.params.q) {
                  $timeout(function () {
                    $rootScope.$emit('librarySearched');
                  });
                }
              } else {
                locationNoReload.skipReload().url(scope.library.url + '/find?q=' + query + '&f=a');
              }

              $routeParams.q = query;
              $routeParams.f = 'a';

              $timeout(function () {
                $rootScope.$emit('librarySearchChanged', true);
              });
            } else {
              locationNoReload.skipReload().url(scope.library.url);

              $timeout(function () {
                $rootScope.$emit('librarySearchChanged', false);
              });
            }

            prevQuery = query;
          });
        }, 100, {
          'leading': true
        });

        //
        // Watches and listeners.
        //

        // Wait until library data is ready before processing information to display the library card.
        scope.$watch('loading', function (newVal) {
          if (!newVal) {
            augmentData();
            adjustFollowerPicsSize();
          }
        });

        var deregisterLibraryUpdated = $rootScope.$on('libraryUpdated', function (e, library) {
          if (library.id === scope.library.id) {
            _.assign(scope.library, library);
            augmentData();
            adjustFollowerPicsSize();
          }
        });
        scope.$on('$destroy', deregisterLibraryUpdated);

        // Update how many follower pics are shown when the window is resized.
        var adjustFollowerPicsSizeOnResize = _.debounce(adjustFollowerPicsSize, 200);
        $window.addEventListener('resize', adjustFollowerPicsSizeOnResize);
        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', adjustFollowerPicsSizeOnResize);
        });
      }
    };
  }
]);

