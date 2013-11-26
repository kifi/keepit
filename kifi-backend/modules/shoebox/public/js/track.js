// Tracks user actions using mixpanel. Also requires jQuery.

(function() {
  'use strict';

  var thingsToTrack = {
    yourKeeps: {
      selector: '.my-keeps'
    },
    yourFriends: {
      selector: '.my-friends'
    },
    installExtension: {
      selector: '.install-kifi'
    },
    tagResults: {
      selector: '.collection'
    },
    kifiBlog: {
      selector: '.updates-features'
    },
    previewKeep: {
      selector: '.keep'
    }
  };

  function trackEvent(properties) {
    mixpanel.track('beta_clicked_internal_page', properties);
  }

  function defaultHandler(type, spec) {
    trackEvent({
      type: type,
      where: window.location.pathname.slice(1).split('/'),
      what: spec.selector
    });
  }

  for (var type in thingsToTrack) {
    var spec = thingsToTrack[type];
    var events = spec.events || 'click';
    var handler = spec.handler || defaultHandler;
    $(document).on(events, spec.selector, handler.bind(document, type, spec));
  }
})();
