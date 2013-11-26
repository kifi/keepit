/**
 * ---------------
 *      Track
 * ---------------
 *
 * Contains functionality to track user interactions on the Site.
 * It only requires the mixpanel tag to be on the page (+jquery),
 * Everything else is stand alone (i.e. it attaches itself to the elements)
 */

(function() {
  'use strict';

  var thingsToTrack = {
    yourKeeps: {
      selector: ".my-keeps",
      events: ['click']
    },
    yourFriends: {
      selector: ".my-friends",
      events: ['click']
    },
    installExtension: {
      selector: ".install-kifi",
      events: ['click']
    },
    tagResults: {
      selector: ".collection",
      events: ['click']
    },
    kifiBlog: {
      selector: ".updates-features",
      events: ['click']
    },
    previewKeep: {
      selector: ".keep",
      events: ['click']
    }
  }

  function trackEvent(properties) {
    mixpanel.track("beta_clicked_internal_page", properties);
  }

  function defaultEventHandler(tpe, event) {
    var properties = {
      'type' : tpe,
      'where': window.location.pathname.slice(1).split("/"),
      'what' : [event.target.className, event.target.id]
    };
    trackEvent(properties);
  }

  function attachEventHandlers() {
    var spec, handler;
    for (var type in thingsToTrack){
      spec = thingsToTrack[type];
      handler = spec.handler || defaultEventHandler;
      $(document).on(spec.events.join(" "), spec.selector, handler.bind(null, type));
    };
  }

  attachEventHandlers();

})();
