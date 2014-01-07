// Tracks user actions using mixpanel. Also requires jQuery.

(function() {
  'use strict';

  var lastLocation;

  var thingsToTrack = {
    preview: {
      selector: '.keep'
    },
    tellUs: {
      selector: '.tell-us'
    },
    sendFeedback: {
      selector: '.send-feedback'
    },
    viewTeam: {
      selector: '.view-team'
    },
    viewEngBlog: {
      selector: '.view-eng-blog'
    },
    contactUs: {
      selector: '.contact-us'
    },
    jobs: {
      selector: '.join-us'
    },
    addFriends: {
      selector: '.add-friends'
    },
    inviteFriend: {
      selector: '.invite-button'
    },
    unFriend: {
      selector: '.friend-status'
    },
    hideFriendInSearch: {
      selector: '.friend-mute'
    },
    searchKifi: {
      selector: '.query',
      events: 'keypress'
    },
    searchContacts: {
      selector: '.friends-filter',
      events: 'keypress'
    }
  };

  var locations = {
    yourKeeps: /^\/$/,
    yourFriends: /^\/friends$/,
    tagResults: /^\/tag/,
    searchResults: /^\/find/,
    addFriends: /^\/friends\/(invite|find)$/,
    requests: /^\/friends\/requests$/,
    kifiBlog: /^\/blog$/
  };

  function getLocation(path) {
    path = path || lastLocation || window.location.pathname;
    for (var loc in locations){
      if (locations[loc].test(path)) {
        return loc;
      }
    }
    return path;
  }

  function defaultClickHandler(action) {
    mixpanel.track('user_clicked_internal_page',{
      type: getLocation(),
      action: action,
      origin: window.location.origin
    });
  }

  function defaultViewHandler(path) {
    mixpanel.track('user_viewed_internal_page',{
      type: getLocation(path),
      origin: window.location.origin
    });
  }

  kifiViewTracker.forEach(function(path){
    defaultViewHandler(path);
  });
  kifiViewTracker = {
    push: function(path){
      lastLocation = path;
      defaultViewHandler(path);
    }
  };

  for (var action in thingsToTrack) {
    var spec = thingsToTrack[action];
    var events = spec.events || 'click';
    var handler = spec.handler || defaultClickHandler;
    $(document).on(events, spec.selector, handler.bind(document, action));
  }

})();
