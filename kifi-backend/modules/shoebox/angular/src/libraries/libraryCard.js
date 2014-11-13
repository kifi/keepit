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
        toggleEdit: '=',
        librarySearch: '='
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
        var updateSearchText = false;

        var kfColsElement = angular.element('.kf-cols');
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
        scope.librarySearchInProgress = scope.librarySearch;
        scope.librarySearchBarShown = false;
        scope.search = { 'text': $routeParams.q || '' };
        scope.pageScrolled = false;

        var magicImages = {
          'l7SZ3gr3kUQJ': '//djty7jcqog9qu.cloudfront.net/special-libs/l7SZ3gr3kUQJ.png',
          'l4APrlM5wzaM': '//djty7jcqog9qu.cloudfront.net/special-libs/l4APrlM5wzaM.png',
          'l2iJXRO7vtoa': '//djty7jcqog9qu.cloudfront.net/special-libs/l2iJXRO7vtoa.png',
          'l292wb07mhuB': '//djty7jcqog9qu.cloudfront.net/special-libs/l292wb07mhuB.png',
          'lGcw3PhnD9Wo': '//djty7jcqog9qu.cloudfront.net/special-libs/lGcw3PhnD9Wo.png',
          'l3ai2ejn5t9L': '//djty7jcqog9qu.cloudfront.net/special-libs/l3ai2ejn5t9L-2.jpg',
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
        function init() {
          if (scope.isUserLoggedOut && !platformService.isSupportedMobilePlatform()) {
            showKfColsOverflow();
            $timeout(hideKfColsOverflow);
          }
        }

        function hideKfColsOverflow() {
          // Hide overflow so that there is no horizontal scrollbar due to the very
          // wide white background on the library header.
          kfColsElement.css({ 'overflow-x': 'hidden' });
        }

        function showKfColsOverflow() {
          // Show overflow so that infinite scroll can be initialized correctly.
          kfColsElement.css({ 'overflow-x': 'visible' });
        }

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
          return libraryService.isMyLibrary(library);
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
        scope.canFollowLibrary = function (library) {
          return !scope.alreadyFollowingLibrary(library) && !scope.isMyLibrary(library);
        };

        scope.alreadyFollowingLibrary = function (library) {
          return libraryService.isFollowingLibrary(library);
        };

        scope.followLibrary = function (library) {
          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedFollowButton' });

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
            return signupService.register({libraryId: scope.library.id});
          }

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
          // TODO(yrl): ask Jen about whether we can remove this.
          libraryService.trackEvent('user_clicked_page', library, { action: 'unfollow' });

          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedUnfollowButton' });
          libraryService.leaveLibrary(library.id)['catch'](modalService.openGenericErrorModal);
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

        function positionSearchFollow() {
          $timeout(function () {
            searchFollowElement.css({
              'left': headerLinksElement.offset().left + headerLinksElement.width() + 15 + 'px'
            });
          }, 200);
        }

        function showLibrarySearchBar() {
          if (platformService.isSupportedMobilePlatform() || scope.librarySearchBarShown) {
            return;
          }

          scope.librarySearchInProgress = true;
          scope.librarySearchBarShown = true;

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

        scope.onSearchInputFocus = function () {
          // Track click/focus on search bar.
          $rootScope.$emit('trackLibraryEvent', 'click', {
            action: platformService.isSupportedMobilePlatform() ? 'clickedSearchPinned' : 'clickedSearchBody'
          });

          showLibrarySearchBar();
        };

        scope.onSearchExit = function () {
          scrollToTop();

          if (scope.isUserLoggedOut && !platformService.isSupportedMobilePlatform()) {
            showKfColsOverflow();
          }
          locationNoReload.skipReload().url(scope.library.url);
          locationNoReload.reloadNextRouteChange();

          scope.librarySearchInProgress = false;
          scope.librarySearchBarShown = false;
          $rootScope.$emit('librarySearchChanged', false);
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
            scope.search.text = '';
          });
        };

        scope.onSearchInputChange = _.debounce(function (query) {
          $timeout(function () {
            if (query) {
              locationNoReload.cancelReloadNextRouteChange();

              if (prevQuery) {
                if (scope.isUserLoggedOut && !platformService.isSupportedMobilePlatform()) {
                  showKfColsOverflow();
                }
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
                if (scope.isUserLoggedOut && !platformService.isSupportedMobilePlatform()) {
                  showKfColsOverflow();
                }
                locationNoReload.skipReload().url(scope.library.url + '/find?q=' + query + '&f=a');
              }

              $routeParams.q = query;
              $routeParams.f = 'a';

              $timeout(function () {
                if (platformService.isSupportedMobilePlatform()) {
                  scope.librarySearchInProgress = true;  // For mobile.
                }
                $rootScope.$emit('librarySearchChanged', true);
              });

              prevQuery = query;
            } else {
              if (scope.isUserLoggedOut && !platformService.isSupportedMobilePlatform()) {
                showKfColsOverflow();
              }
              locationNoReload.skipReload().url(scope.library.url);
              locationNoReload.reloadNextRouteChange();
              prevQuery = '';

              $timeout(function () {
                if (platformService.isSupportedMobilePlatform()) {
                  scope.librarySearchInProgress = false;  // For mobile.
                }
                $rootScope.$emit('librarySearchChanged', false);
                scope.search.text = '';
              });
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

            if (scope.librarySearch) {
              $timeout(function () {
                showLibrarySearchBar();
              });
            }
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

        var deregisterNewSearchUrl = $rootScope.$on('newSearchUrl', function () {
          updateSearchText = true;
        });
        scope.$on('$destroy', deregisterNewSearchUrl);

        var deregisterNewSearchQuery = $rootScope.$on('newSearchQuery', function (e, query) {
          if (updateSearchText) {
            scope.search.text = query;
            updateSearchText = false;
          }
        });
        scope.$on('$destroy', deregisterNewSearchQuery);

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


        init();
      }
    };
  }
]);

