'use strict';

angular.module('kifi')

.factory('abTestService', ['$window',
  function ($window) {
    // Currently active A/B tests, keyed by id.
    var abTests = {
      '1': {
        name: 'exp_follow_popup',
        treatment_duration: 60 * 60 * 24,  // one day
        treatments: [
          {
            name: 'none',
            isControl: true
          },
          {
            name: 'popupLibrary',
            data: {
              buttonText: 'Follow',
              mainText: 'Join Kifi to follow this library.<br/>Discover other libraries,<br/>and build your own!',
              quote: 'From business to personal, Kifi has been<br/>instrumental in my day-to-day life.',
              quoteAttribution: 'Remy Weinstein, California'
            }
          },
          {
            name: 'popupCollection',
            data: {
              buttonText: 'Save',
              mainText: 'Join Kifi to save this collection.<br/>Discover other collections,<br/>and build your own!',
              quote: 'From business to personal, Kifi has been<br/>instrumental in my day-to-day life.',
              quoteAttribution: 'Remy Weinstein, California'
            }
          }
        ]
      }
    };

    var mixPanelId = $window.mixpanel &&
      $window.mixpanel.cookie &&
      $window.mixpanel.cookie.props &&
      $window.mixpanel.cookie.props.distinct_id;

    /**
     * Return a new experiment based on the configuration of the passed-in abTest object.
     */
    function Experiment(abTest) {
      this.name = abTest.name;

      var treatmentIndex = parseInt(mixPanelId.slice(-14).replace('-', ''), 16) % abTest.treatments.length;

      var treatment = abTest.treatments[treatmentIndex];

      this.treatment_name = treatment.name;
      this.treatment_data = treatment.data || false;
      this.treatment_is_control = !!treatment.isControl;
    }

    function getExperiment(abTestId) {
      var abTest = abTests[abTestId];

      if (!abTest || !mixPanelId) {
        return null;
      }

      return new Experiment(abTest);
    }


    // API
    return {
      getExperiment: getExperiment
    };
  }
]);
