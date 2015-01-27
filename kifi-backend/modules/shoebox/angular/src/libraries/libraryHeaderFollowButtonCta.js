'use strict';

angular.module('kifi')

.directive('kfLibraryHeaderFollowButtonCta', [
  '$compile', '$rootElement', '$templateCache', '$timeout', '$window', 'libraryService', 'platformService',
  function ($compile, $rootElement, $templateCache, $timeout, $window, libraryService, platformService) {
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

            // Center the CTA with respect to the follow button.
            var left = elementLeft - Math.round((cta.outerWidth() - element.outerWidth()) / 2);

            cta.css({top: top + 'px', left: left + 'px'});
            ctaOuterArrow.css({top: ctaOuterArrowTop + 'px'});
            ctaInnerArrow.css({top: ctaInnerArrowTop + 'px'});

            scope.$evalAsync(function () {
              cta.show();

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

        // TODO: make follow CTA sticky when follow button is sticky.
        $window.addEventListener('scroll', hideCTA);
        scope.$on('$destroy', function () {
          $window.removeEventListener('scroll', hideCTA);
        });


        //
        // On link.
        //
        if (scope.isUserLoggedOut) {
          // A/B test experiments.
          var experiment = scope.library.abTestExperiment;
          if (!experiment) {
            return;
          }

          if (platformService.isSupportedMobilePlatform() ||
            experiment.treatment_is_control || !experiment.treatment_data ||
            !experiment.treatment_data.mainText || !experiment.treatment_data.quote || !experiment.treatment_data.quoteAttribution
          ) {
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
          scope.ctaMainText = experiment.treatment_data.mainText;
          scope.ctaQuote = experiment.treatment_data.quote;
          scope.ctaQuoteAttribution = experiment.treatment_data.quoteAttribution;

          // Show the CTA after a certain amount of time.
          var autoShowCTAPromise = $timeout(function () {
            showCTA(true);
            trackHover('timer');
          }, 5000);
        }
      }
    };
  }
]);
