#!/usr/bin/env node

/*eslint-env node */

var _ = require('lodash');
var async = require('async');
var crypto = require('crypto');
var https = require('https');
var fs = require('fs');
var Promise = require('bluebird');
var querystring = require('querystring');
var readline = require('readline');

var AMPLITUDE_API_KEY = '5a7a940f68887487129b20a4cbf0622d';

// mixpanel properties we don't care about
var deletedProperties = [
  'clientBuild', 'device', 'experiments',
  'extensionVersion', 'kcid_6', 'kcid_7', 'kcid_8', 'kcid_9', 'kcid_10',
  'kcid_11', 'remoteAddress', 'serviceInstance',
  'serviceZone', 'userId', 'userSegment'
];

// do not migrate these events
// - `string` type is the event name
// - `regexp` type is a regex to match against the event name
// - `function` type takes the mixpanel JSON, returns true if we should skip the event
var skippedEvents = ['user_old_slider_sliderShown', 'user_expanded_keeper', 'user_used_kifi',
  'user_reco_action', 'user_logged_in', 'visitor_expanded_keeper', 'visitor_reco_action',
  'visitor_viewed_notification', 'visitor_clicked_notification', /anonymous_/,
  function(mpEvent) { return _.startsWith(mpEvent.properties.userAgent, 'Pingdom'); }
];

// rename these mixpanel events to these amplitude events
var renamedEvents = {
  'user_modified_library': 'user_created_library',
  'user_changed_setting': 'user_changed_settings',
  '$email': 'email'
};

// names of mixpanel properties that should be amplitude user properties
var userProperties = [
  'firstName', 'lastName', '$email', 'gender', 'keeps', 'kifiConnections',
  'privateKeeps', 'publicKeeps', 'socialConnections', 'tags',
  'daysSinceLibraryCreated', 'daysSinceUserJoined'
];

// any experiment properties not in this list will not be sent to amplitude
var experimentsToKeep = [
  'exp_organization', 'exp_explicit_social_posting', 'exp_related_page_info', 'exp_new_keep_notifications',
  'exp_activity_email', 'exp_gratification_email'
];

// mapping of mixpanel property keys to amplitude default user properties
var defaultUserProperties = {
  userId: 'user_id',
  distinct_id: 'device_id',
  clientVersion: 'app_version',
  client: 'platform',
  os: 'os_name',
  osVersion: 'os_version',
  $country: 'country',
  $region: 'region',
  $city: 'city'
};

// function(url) - https GET request that return a promise
var httpsGet = Promise.method(function(url) {
  return new Promise(function(resolve, reject) {
    https.get(url, resolve).on('error', reject);
  });
});

/**
 * @param mpEvent mixpanel mpEvent
 * @return {boolean} true if we should not send this event to amplitude
 */
function isSkippedEvent(mpEvent) {
  return _.some(skippedEvents, function(arg) {
    if (_.isRegExp(arg)) {
      return arg.test(mpEvent.event);
    } else if (_.isFunction(arg)) {
      return arg(mpEvent);
    }
    return arg === mpEvent.event;
  });
}

/**
 * @return {boolean} true if we don't care to send this property to amplitude as a user or event property
 */
function isDeletedProperty(key, _value) {
  return _.contains(deletedProperties, key) ||
    (_.startsWith(key, 'exp_') && !_.contains(experimentsToKeep, key));
}

/**
 *
 * @param mixpanelEvent
 * @return {string} the value of the "event_type" field for an amplitude event
 */
function amplitudeEventName(mixpanelEvent) {
  var newEventName = renamedEvents[mixpanelEvent.event];
  if (_.isString(newEventName)) {
    return newEventName;
  } else if (mixpanelEvent.event === 'user_joined' && mixpanelEvent.properties.action === 'registered') {
    return 'user_registered';
  } else if (mixpanelEvent.event === 'user_joined' && mixpanelEvent.properties.action === 'installed') {
    return 'user_installed';
  } else {
    return mixpanelEvent.event;
  }
}

/**
 *
 * @param mixpanelEvent
 * @return {number} epoch UTC in milliseconds
 */
function amplitudeTimestamp(mixpanelEvent) {
  // mixpanel: returned timestamps are expressed in seconds since January 1, 1970 in your project's timezone, not UTC
  // https://mixpanel.com/docs/api-documentation/exporting-raw-data-you-inserted-into-mixpanel

  var epochSecondsInPST = mixpanelEvent.properties.time;
  var pstOffset = 8 * 60 * 60; // 8 hr * 60 min * 60 sec
  var epochSecondsInUTC = epochSecondsInPST + pstOffset;
  return epochSecondsInUTC * 1000; // to milliseconds
}

function amplitudeEvent(mixpanelEvent, insertId) {
  var event = {};
  event.event_type = amplitudeEventName(mixpanelEvent);
  event.time = amplitudeTimestamp(mixpanelEvent);

  var props = getUserAndEventProperties(mixpanelEvent);
  event.user_properties = props.userProperties;
  event.event_properties = props.eventProperties;

  _.each(defaultUserProperties, function(amplitudeKey, mixpanelKey) {
    event[amplitudeKey] = mixpanelEvent.properties[mixpanelKey];
  });

  // amplitude will automatically dedupe events with the same insert_id:
  //   "a unique identifier for the event being inserted; we will deduplicate
  //    events with the same insert_id sent within 24 hours of each other"
  event.insert_id = insertId;

  return event;
}

function isUserProperty(key, value) {
  return _.includes(userProperties, key) ||
    (_.startsWith(key, 'user') && key !== 'userAgent') ||
    _.startsWith(key, 'kcid') ||
    _.startsWith(key, 'exp_');
}

function renameProperty(key) {
  if (key === 'kifiInstallationId') return 'installation_id';
  if (key === 'userCreatedAt') return 'created_at';
  if (key === '$email') return 'email';
  return _.snakeCase(key);
}

var unsupportedPropertyKeys = {};
var defaultUserPropertyKeys = _.keys(defaultUserProperties);

function getUserAndEventProperties(mixpanelEvent) {
  var userProperties = {}, eventProperties = {};

  _.each(mixpanelEvent.properties, function(value, key) {
    if (_.contains(defaultUserPropertyKeys, key)) return;
    if (isDeletedProperty(key, value)) return;
    if (_.isEmpty(value)) return;
    if (_.isArray(value)) {
      unsupportedPropertyKeys[key] = value;
      return;
    }

    var newKey = renameProperty(key);
    if (isUserProperty(key, value)) {
      userProperties[newKey] = value;
    } else {
      eventProperties[newKey] = value;
    }
  });

  return {
    userProperties: userProperties,
    eventProperties: eventProperties
  };
}

/**
 * Send event to amplitude
 * @param event
 * @return {Promise}
 */
function sendAmplitudeEvent(event) {
  var queryParams = {
    event: JSON.stringify(event),
    api_key: AMPLITUDE_API_KEY
  };

  var query = querystring.stringify(queryParams);
  var endpoint = 'https://api.amplitude.com/httpapi?' + query;
  return httpsGet(endpoint);
}

function apiWorker(task, done) {
  sendAmplitudeEvent(task.event)
    .then(handleApiResponse(task))
    .then(function(success) {
      done(null, success);
    })
    .catch(function(err) {
      done(err);
    });
}

var handleApiResponse = function(task) {
  return Promise.method(function(res) {
    return new Promise(function(resolve, reject) {
      res.setEncoding('utf8');

      var resBody = '';
      res.on('data', function (chunk) {
        resBody += chunk;
      });

      res.on('end', function () {
        var result = {statusCode: res.statusCode, message: resBody, event: task.event};

        if (res.statusCode !== 200) {
          reject(result);
        } else {
          resolve(result);
        }
      });
    });
  });
};

function hashString(str) {
  var hash = crypto.createHash('sha1');
  hash.update(str);
  return hash.digest('hex');
}

// have no more than 50 requests in flight at a time
var amplitudeApiQueue = async.queue(apiWorker, 50);
var successCounter = 0;
var failCounter = 0;

function printQueueState() {
  console.log("Amplitude API Queue: success=%d fail=%d pending=%d running=%d",
    successCounter, failCounter, amplitudeApiQueue.length(), amplitudeApiQueue.running());
}

var filename = process.argv[2];
if (!_.isString(filename) || !fs.statSync(filename).isFile()) {
  console.error('USAGE: amplitude_import.js FILE');
  process.exit(1);
}

var failedEvents = [];

var rd = readline.createInterface({
  input: fs.createReadStream(filename),
  output: process.stdout,
  terminal: false
});

setInterval(printQueueState, 1000);

rd.on('line', function(line) {
  // TODO throttle reading lines to the pace we're sending API requests, we don't need to hold the whole file in memory

  var mpEvent = JSON.parse(line);
  if (isSkippedEvent(mpEvent)) {
    return;
  }

  var insertId = hashString(line);
  var event = amplitudeEvent(mpEvent, insertId);
  var task = {
    event: event
  };

  amplitudeApiQueue.push(task, function(err, result) {
    if (err) {
      // TODO handle and retry on EHOSTUNREACH, ETIMEDOUT errors
      failedEvents.push(err);
      console.error('[ERROR]', err);
      console.log('[MIXPANEL]', mpEvent);
      failCounter += 1;
    } else {
      successCounter += 1;
      //console.info('[DONE]', result);
    }
  });
});
