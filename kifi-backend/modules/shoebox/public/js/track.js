// Tracks user actions using mixpanel. Also requires jQuery.

(function() {
  'use strict';

  var thingsToTrack = {
    unkeep: {
      selector: '.page-keep'
    },
    preview: {
      selector: '.keep'
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
      selector: '.query-wrap',
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
    requests: /^\/friends\/requests$/ ,
    kifiBlog: /^\/blog$/,
  };

  function getLocation() {
    var path = window.location.pathname;
    for (var loc in locations){
      if (locations[loc].test(path)) {
        return loc;
      }
    }
  }

  function trackClick(properties) {
    mixpanel.track('clicked_internal_page', properties);
  }

  function defaultClickHandler(action) {
    trackClick({
      type: getLocation(),
      action: action
    });
  }

  for (var action in thingsToTrack) {
    var spec = thingsToTrack[action];
    var events = spec.events || 'click';
    var handler = spec.handler || defaultClickHandler;
    $(document).on(events, spec.selector, handler.bind(document, action));
  }

})();
