'use strict';

angular.module('kifi')

.directive('kfLibraryHeaderFollowButtonCta', [
  '$compile', '$rootElement', '$rootScope', '$templateCache', '$timeout', '$window', 'libraryService', 'platformService',
  function ($compile, $rootElement, $rootScope, $templateCache, $timeout, $window, libraryService, platformService) {
    return {
      restrict: 'A',
      link: function (scope, element/*, attrs */) {
        function showCTA(showX) {
          if (cta) {
            scope.showX = showX;

            var elementTop = element.offset().top;
            var elementLeft = element.offset().left;

            var ctaHeight = cta.outerHeight();
            var ctaMargin = 15;  // Vertical spacing between CTA and the follow button.
            var ctaHeightWithMargin = ctaHeight + 15;

            // By default, we try to show the CTA above the follow button.
            var top = elementTop - ctaHeightWithMargin;
            var ctaOuterArrowTop = ctaHeight - 2;
            var ctaInnerArrowTop = ctaOuterArrowTop - 1;
            scope.ctaShownAbove = true;

            // However, if there is no room between the follow button and the
            // Kifi header, then we show the CTA beneath the follow button.
            if (elementTop - ctaHeightWithMargin - angular.element($window).scrollTop() < kfHeaderHeight) {
              top = elementTop + element.outerHeight() + ctaMargin;
              ctaOuterArrowTop = -ctaArrowHeight;
              ctaInnerArrowTop = ctaOuterArrowTop + 1;
              scope.ctaShownAbove = false;
            }

            // If the follow button is "sticky" (i.e., its position is fixed),
            // similarly fix the CTA's position so that it won't move upon scroll.
            var position = 'absolute';
            if (element.css('position') === 'fixed') {
              position = 'fixed';
              top = top - angular.element($window).scrollTop();
            }

            // Center the CTA with respect to the follow button.
            var left = elementLeft - Math.round((cta.outerWidth() - element.outerWidth()) / 2);

            cta.css({position: position, top: top + 'px', left: left + 'px'});
            ctaOuterArrow.css({top: ctaOuterArrowTop + 'px'});
            ctaInnerArrow.css({top: ctaInnerArrowTop + 'px'});

            scope.$evalAsync(function () {
              cta.show();
              ctaShown = true;

              if (autoShowCTAPromise) {
                $timeout.cancel(autoShowCTAPromise);
                autoShowCTAPromise = null;
              }
            });
          }
        }

        function hideCTA() {
          if (cta) {
            cta.hide();
          }
        }

        function autoShowCTA(timeInMS) {
          // Show the CTA only if the page is visible and if it hasn't
          // been shown before.

          // $document.hidden is undefined; use $window.document to get
          // around this problem.
          if (!$window.document.hidden) {
            if (autoShowCTAPromise) {
              $timeout.cancel(autoShowCTAPromise);
              autoShowCTAPromise = null;
            }

            autoShowCTAPromise = $timeout(function () {
              if (!$window.document.hidden && !ctaShown) {
                showCTA(true);
                ctaShown = true;
                trackHover('timer');
              }
            }, timeInMS);
          }
        }

        function autoShowCTAQuickly() {
          autoShowCTA(2000);
        }

        function trackHover(trigger) {
          libraryService.trackEvent('visitor_viewed_page', scope.library, {
            type: 'libraryLanding',
            viewType: 'hover',
            subtype: 'hoverFollowButton',
            trigger: trigger
          });
        }

        function trackCTAClose() {
          libraryService.trackEvent('visitor_clicked_page', scope.library, {
            type: 'libraryLanding',
            action: 'clickedFollowCTAClose'
          });
        }


        //
        // Scope methods.
        //
        scope.closeCTA = function () {
          hideCTA();
          trackCTAClose();
        };


        //
        // Event listeners.
        //
        element.on('mouseenter', function () {
          showCTA(false);
          trackHover('hover');
        });
        element.on('mouseleave', hideCTA);


        //
        // On link.
        //
        if (scope.isUserLoggedOut) {
          var treatment = scope.library.abTestTreatment;
          if (!treatment || treatment.isControl || platformService.isSupportedMobilePlatform()) {
            return;
          }

          // Create CTA tooltip.
          var cta = angular.element($templateCache.get('libraries/libraryHeaderFollowButtonCta.tpl.html'));
          $rootElement.find('html').append(cta);
          $compile(cta)(scope);

          var ctaOuterArrow = cta.find('.kf-lh-fbc-arrow-outer');
          var ctaInnerArrow = cta.find('.kf-lh-fbc-arrow-inner');
          var ctaArrowHeight = 15;

          // The CTA is initially hidden.
          cta.hide();

          var kfHeaderHeight = angular.element('.kf-header').outerHeight();

          // Populate the CTA content.
          scope.ctaMainText = treatment.data.mainText;
          scope.ctaQuote = treatment.data.quote;
          scope.ctaQuoteAttribution = treatment.data.quoteAttribution;

          // Show the CTA after a certain amount of time.
          var ctaShown = false;
          var autoShowCTAPromise = null;
          autoShowCTA(5000);

          // Show the CTA automatically when the user returns to the library page.
          document.addEventListener('visibilitychange', autoShowCTAQuickly);
          scope.$on('$destroy', function () {
            $window.removeEventListener('visibilitychange', autoShowCTAQuickly);
          });
        }

        var deregisterStateChange = $rootScope.$on('$stateChangeSuccess', function (event, toState) {
          if (!toState.name.match(/^library\.keeps/)) {
            $timeout.cancel(autoShowCTAPromise);
            autoShowCTAPromise = null;
          }
        });
        scope.$on('$destroy', deregisterStateChange);
      }
    };
  }
]);
