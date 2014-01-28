// Tracks user actions using mixpanel. Also requires jQuery.

(function() {
  'use strict';

  var userInfo;
  var lastLocation;
  var identifiedViewEventQueue = [];

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
    var oldId;
    if (userInfo && userInfo.id) {
      oldId = mixpanel.get_distinct_id && mixpanel.get_distinct_id();
	  if (oldId) {
		  mixpanel.identify(userInfo.id);
	  }
    }
    mixpanel.track('user_clicked_page',{
      type: getLocation(),
      action: action,
      origin: window.location.origin
    });
    if (oldId) {
      mixpanel.identify(oldId);
    }
  }

  function getUserStatus() {
    var userStatus = "standard";
    if (userInfo && userInfo.experiments) {
      if (userInfo.experiments.indexOf("fake") > -1) {
        userStatus = "fake";
      }
      else if (userInfo.experiments.indexOf("admin") > -1) {
        userStatus = "admin";
      }
    }
    return userStatus;
  }

  function sendIdentifiedView(path) {
    if (userInfo && userInfo.id) {
      var oldId = mixpanel.get_distinct_id && mixpanel.get_distinct_id();
	  if (!oldId) {
		  return;
	  }
      mixpanel.track('user_viewed_page',{
        type: getLocation(path),
        origin: window.location.origin,
        userStatus: getUserStatus()
      });
      mixpanel.identify(oldId);
    } else {
      identifiedViewEventQueue.push(path);
    }
  }

  function sendView(path) {
    sendIdentifiedView(path);
    mixpanel.track('visitor_viewed_page',{
      type: getLocation(path),
      origin: window.location.origin
    });
  }

  userInfo = kifiTracker._user;
  kifiTracker._views.forEach(function(path) {
    sendView(path);
  });


  kifiTracker = {
    view: function(path) {
      sendView(path);
    },
    setUserInfo: function(user) {
      userInfo = user;
      var toSend = identifiedViewEventQueue.slice();
      identifiedViewEventQueue.length=0;
      toSend.forEach(function(path) {
        sendIdentifiedView(path);
      });
    }
  }

  for (var action in thingsToTrack) {
    var spec = thingsToTrack[action];
    var events = spec.events || 'click';
    var handler = spec.handler || defaultClickHandler;
    $(document).on(events, spec.selector, handler.bind(document, action));
  }

})();
