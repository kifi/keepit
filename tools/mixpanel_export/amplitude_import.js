#!/usr/bin/env node

/* eslint-env node, amd */

/*
 * usage examples:
 *    USE_FAKE_API=1 LOGGER_LEVEL=warn ./amplitude_import.js exports/mixpanel-20150707-20150707.txt
 *    ./amplitude_import.js exports/mixpanel-20150707-20150707.txt
 */

var _ = require('lodash');
var async = require('async');
var crypto = require('crypto');
var https = require('https');
var fs = require('fs');
var LineByLineReader = require('line-by-line');
var path = require('path');
var Promise = require('bluebird');
var querystring = require('querystring');
var winston = require('winston');
var spawnSync = require('child_process').spawnSync;

var USE_FAKE_API = !!_.get(process.env, 'USE_FAKE_API', false);
var CONCURRENT_API_CALLS = parseInt(_.get(process.env, 'CONCURRENT_API_CALLS', 100), 10);

// var AMPLITUDE_API_KEY = '5a7a940f68887487129b20a4cbf0622d'; // Test
// var AMPLITUDE_API_KEY = 'ca7e6c5bdd95ed9e43ffb7c106479e17'; // Test 2
var AMPLITUDE_API_KEY = '3e8539247e1f053efbe422220e682854'; // Test 3

var filename = process.argv[2];
if (!_.isString(filename) || !fs.statSync(filename).isFile()) {
  console.error('USAGE: amplitude_import.js FILE');
  process.exit(1);
}

// logging config
var filenameExt = _.endsWith(filename, '.txt.bz2') ? '.txt.bz2' : '.txt';
var logfile = 'logs/' + path.basename(filename, filenameExt) + '.log';
var logger = new (winston.Logger)({
  transports: [
    new (winston.transports.Console)({ level: _.get(process.env, 'LOGGER_LEVEL', 'debug') }),
    new (winston.transports.File)({ filename: logfile, level: 'info' })
  ]
});
winston.addColors({debug: 'blue', info: 'green', warn: 'yellow', error: 'red'});

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
  function(mpEvent) { return _.startsWith(mpEvent.properties.userAgent, 'Pingdom'); },
  function(mpEvent) {
    var typeProperty = mpEvent.properties.type;
    // skip {user,visitor}_viewed_page events where the "type" property starts with a "/", with a few exceptions
    return _.endsWith(mpEvent.event, '_viewed_page') && _.isString(typeProperty) && typeProperty.charAt(0) === '/' &&
      typeProperty !== '/settings' && typeProperty !== '/tags/manage' && !_.startsWith(typeProperty, '/?m=0');
  },
  function(mpEvent) {
    return mpEvent.properties.action === 'importedBookmarks' && mpEvent.event === 'user_joined';
  }
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

// for user_was_notified events, these 'action' properties should be user_clicked_notification events
var userWasNotifiedClickActions = ['open', 'click', 'spamreport', 'cleared', 'marked_read', 'marked_unread'];

// for user_joined events that will be renamed to user_registered based on the 'action' property
var userJoinedRegisteredActions = ['wasInvited', 'registered'];

// many property values will be automatically convereted from camelCase to snake_case,
// but the following fields should not be changed
var fieldNamesToNotChangeValuesToSnakeCase = [
  'library_owner_user_name', '$email', 'os_name', 'os_version', 'country', 'region', 'city', 'agent', 'user_agent',
  'service_version', 'operating_system', 'origin', 'current_url', 'browser_details', 'browser', 'created_at',
  'language'
];

var fieldsToNotChangeValuesToSnakeCase = [
  function(field) {
    return fieldNamesToNotChangeValuesToSnakeCase.indexOf(field) >= 0 ||
      _.startsWith(field, 'kcid') ||
      _.startsWith(field, 'utm_') ||
      _.endsWith(field, '_id') ||
      _.endsWith(field, '_version');
  }
];

// do not convert these values from camelCase to snake_case
var valuesThatAreOkayAsCamelCase = ['iPad', 'iPhone', 'iOS'];

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
  var action = mixpanelEvent.properties.action;
  if (_.isString(newEventName)) {
    return newEventName;
  } else if (mixpanelEvent.event === 'user_joined' && userJoinedRegisteredActions.indexOf(action) >= 0, action) {
    return 'user_registered';
  } else if (mixpanelEvent.event === 'user_joined' && action === 'installedExtension') {
    return 'user_installed';
  } else if (mixpanelEvent.event === 'visitor_viewed_pane') {
    return 'visitor_viewed_page';
  } else if (mixpanelEvent.event === 'user_viewed_pane') {
    return 'user_viewed_page';
  } else if (mixpanelEvent.event === 'user_was_notified' && userWasNotifiedClickActions.indexOf(action) >= 0) {
    return 'user_clicked_notification';
  } else {
    return mixpanelEvent.event;
  }
}

// offset in seconds to UTC
var pstOffset = 8 * 60 * 60; // 8 hr * 60 min * 60 sec

/**
 *
 * @param mixpanelEvent
 * @return {number} epoch UTC in milliseconds
 */
function amplitudeTimestamp(mixpanelEvent) {
  // mixpanel: returned timestamps are expressed in seconds since January 1, 1970 in your project's timezone, not UTC
  // https://mixpanel.com/docs/api-documentation/exporting-raw-data-you-inserted-into-mixpanel

  var epochSecondsInPST = mixpanelEvent.properties.time;
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
    var value = mixpanelEvent.properties[mixpanelKey];
    event[amplitudeKey] = convertValueToSnakeCase(amplitudeKey, value);
  });

  // copy these system property to also be event properties
  if (mixpanelEvent.properties.os) {
    event.event_properties.operating_system = convertValueToSnakeCase('operating_system', mixpanelEvent.properties.os);
  }

  if (mixpanelEvent.properties.client) {
    event.event_properties.client = convertValueToSnakeCase('client', mixpanelEvent.properties.client);
  }

  // amplitude will automatically dedupe events with the same insert_id:
  //   "a unique identifier for the event being inserted; we will deduplicate
  //    events with the same insert_id sent within 24 hours of each other"
  event.insert_id = insertId;

  // user_viewed_page/visitor_viewed_page or _pane events have special rewrite rules
  if (/^(user|visitor)_viewed_pa[gn]e$/.test(mixpanelEvent.event)) {
    event = modifyViewedPageOrPaneEvent(mixpanelEvent, event);
  }

  return event;
}

var userViewedPagePaneTypes = ['libraryChooser', 'keepDetails', 'messages:all', 'composeMessage', 'createLibrary',
  'chat', 'messages:unread', 'messages:page', 'messages:sent'];

var userViewedPageModalTypes = ['importBrowserBookmarks', 'import3rdPartyBookmarks', 'addAKeep', 'getExtension', 'getMobile'];

var visitorViewedPagePaneTypes = ['login'];

var visitorViewedPageModalTypes = ['libraryLandingPopup', 'signupLibrary', 'signup2Library', 'forgotPassword', 'resetPassword'];

function modifyViewedPageOrPaneEvent(mixpanelEvent, amplitudeEvent) {
  var typeProperty = amplitudeEvent.event_properties.type;
  if (typeProperty === '/settings') {
    amplitudeEvent.event_properties.type = 'settings';
  } else if (typeProperty === '/tags/manage') {
    amplitudeEvent.event_properties.type = 'manageTags';
  } else if (_.startsWith(typeProperty, '/?m=0')) {
    amplitudeEvent.event_properties.type = 'home_feed:successful_signup';
  }

  if (amplitudeEvent.event_type.indexOf('user_') === 0) {
    if (userViewedPagePaneTypes.indexOf(typeProperty) >= 0 || /^guide\d+/.test(typeProperty)) {
      amplitudeEvent.event_properties.page_type = 'pane';
    } else if (userViewedPageModalTypes.indexOf(typeProperty) >= 0) {
      amplitudeEvent.event_properties.page_type = 'modal';
    } else {
      amplitudeEvent.event_properties.page_type = 'page';
    }
  }

  if (amplitudeEvent.event_type.indexOf('visitor_') === 0) {
    if (visitorViewedPagePaneTypes.indexOf(typeProperty) >= 0) {
      amplitudeEvent.event_properties.page_type = 'pane';
    } else if (visitorViewedPageModalTypes.indexOf(typeProperty) >= 0) {
      amplitudeEvent.event_properties.page_type = 'modal';
    } else {
      amplitudeEvent.event_properties.page_type = 'page';
    }
  }

  return amplitudeEvent;
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

function isSimpleMatch(value) {
  return function(pattern) {
    if (_.isRegExp(pattern)) {
      return pattern.test(value);
    } else if (_.isFunction(pattern)) {
      return pattern(value);
    }
    return pattern === value;
  };
}

var isCamelCaseRegExp = /^[a-z]+[a-z0-9]*[A-Z][A-Za-z0-9]*$/;

function isCamelCase(value) {
 return isCamelCaseRegExp.test(value);
}

function shouldConvertValueToSnakeCase(field, value) {
  return _.isString(value) &&
      !_.some(valuesThatAreOkayAsCamelCase, isSimpleMatch(value)) &&
      !_.some(fieldsToNotChangeValuesToSnakeCase, isSimpleMatch(field)) &&
      isCamelCase(value);
}

function convertValueToSnakeCase(field, value) {
  if (shouldConvertValueToSnakeCase(field, value)) {
    return _.snakeCase(value);
  }
  return value;
}

function isPropertyToKeep(key, value) {
  if (_.contains(defaultUserPropertyKeys, key)) {
    return false;
  }
  if (isDeletedProperty(key, value)) {
    return false;
  }
  if (_.isNull(value) || _.isUndefined(value)) {
    return false;
  }
  if (_.isArray(value)) {
    unsupportedPropertyKeys[key] = value;
    return false;
  }

  return true;
}

var propertiesDropped = {};

function getUserAndEventProperties(mixpanelEvent) {
  var userProperties = {}, eventProperties = {};

  _.each(mixpanelEvent.properties, function(value, key) {
    if (!isPropertyToKeep(key, value)) {
      propertiesDropped[key] = true;
      return;
    }

    var newKey = renameProperty(key);
    var propertiesObj = isUserProperty(key, value) ? userProperties : eventProperties;

    propertiesObj[newKey] = convertValueToSnakeCase(newKey, value);
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

function fakeApiWorker(task, done) {
  setTimeout(function() {
    done(null, {event: task.event});
  }, 2); // 2ms artificial delay
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
          logger.error({result: result}, 'bad api response code');
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

// have no more than CONCURRENT_API_CALLS (defaut 100) requests in flight at a time
var amplitudeApiQueue = async.queue(USE_FAKE_API ? fakeApiWorker : apiWorker, CONCURRENT_API_CALLS);
var successCounter = 0;
var skipCounter = 0;
var retryCount = 0;
var eventsByTypeCounter = {};
var failedEvents = [];
var startTime = new Date().getTime();
var lineCounter = 0;

function printQueueState() {
  var basename = path.basename(filename);
  var elapsedSeconds = ((new Date()).getTime() - startTime) / 1000;
  var rate = Math.floor(lineCounter / elapsedSeconds) + ' lines/sec';
  logger.info("[api queue] [%s] rate=%s seconds=%d lines=%d success=%d fail=%d pending=%d running=%d skipped=%d retries=%d",
    basename, rate, elapsedSeconds, lineCounter, successCounter, failedEvents.length, amplitudeApiQueue.length(),
    amplitudeApiQueue.running(), skipCounter, retryCount);
  logger.info("[summary] [%s]", basename, eventsByTypeCounter);
}

if (_.endsWith(filename, 'bz2')) {
  logger.info('running bunzip2 on %s', filename);
  var bunzipProcess = spawnSync('bunzip2', [filename], {stdio: 'inherit'});

  if (bunzipProcess.status !== 0) {
    logger.error('bunzip error', bunzipProcess);
    process.exit(bunzipProcess.status);
  }

  filename = filename.substring(0, filename.length - 4);
}

// save the failed events to disk so they can be retried later
spawnSync('mkdir', ['-p', __dirname + '/failed']);
var failedEventsFilename = 'failed/' + path.basename(filename);
var failedEventsFh = fs.openSync(failedEventsFilename, 'a');

var printQueueStateInterval = setInterval(printQueueState, 5000);

logger.info('START: import from %s', filename);
var reader = new LineByLineReader(filename);

// used to prevent the entire file from being loaded into memory
var readerTaskCheck = setInterval(function() {
  if (amplitudeApiQueue.length() > 500) {
    reader.pause();
  } else {
    reader.resume();
  }
}, 20);

var RETRYABLE_ERRORS = ['ETIMEDOUT', 'ECONNREFUSED', 'EHOSTUNREACH', 'ECONNRESET'];

function recordFailedEvent(err, mpEvent, line) {
  failedEvents.push([err, mpEvent, line]);
  writeFailedEventToDisk(line);
}

function handleLine(line, retries) {
  retries = retries || 0;
  lineCounter++;

  var mpEvent;
  try {
    mpEvent = JSON.parse(line);
  } catch(e) {
    logger.error({line: line}, 'failed to parse line into JSON');
    return;
  }

  if (isSkippedEvent(mpEvent)) {
    skipCounter += 1;
    return;
  }

  var insertId = hashString(line);
  var event = amplitudeEvent(mpEvent, insertId);

  var task = {
    event: event
  };

  if (!event.user_id && !event.device_id) {
    logger.warn('skipping event: missing user_id and device_id for event_type=%s', event.event_type);
    return;
  }

  amplitudeApiQueue.push(task, function(err, result) {
    if (err) {
      if (retries === 0 && err === undefined) {
        logger.error({result: result, mpEvent: mpEvent}, 'undefined error for some reason');
      }

      // retry events for these errors up to 5 times
      if (_.contains(RETRYABLE_ERRORS, err.errno)) {
        retryCount += 1;

        if (retries < 5) {
          setTimeout(function () {
            handleLine(line, retries + 1);
          }, 1000);
        } else {
          logger.error({result: result, err: err}, 'line failed after 5 tries');
          recordFailedEvent(err, mpEvent, line);
        }
      } else {
        logger.error({result: result, err: err}, 'non-retryable error');
        recordFailedEvent(err, mpEvent, line);
      }
    } else {
      successCounter += 1;

      var eventType = result.event.event_type;
      if (eventsByTypeCounter[eventType] !== undefined) {
        eventsByTypeCounter[eventType] += 1;
      } else {
        eventsByTypeCounter[eventType] = 1;
      }
    }
  });
}

function printAndStop() {
  try {
    fs.closeSync(failedEventsFh);
    reader.close();
  } catch(e) {}
  clearInterval(readerTaskCheck);
  clearInterval(printQueueStateInterval);
  printQueueState();
  printFailedEvents();
}

reader.on('line', handleLine);

reader.on('end', function() {
  logger.info('DONE: import from %s', filename);
  var drainEvents = setInterval(function() {
    if (amplitudeApiQueue.length() === 0 && amplitudeApiQueue.running() === 0) {
      clearInterval(drainEvents);
      printAndStop();
    }
  }, 1000);
});

function printFailedEvents() {
  if (failedEvents.length > 0) {
    failedEvents.forEach(function(arr) {
      var err = arr[0];
      logger.info('*************** error:', err.message, err.errno);
      logger.info(arr[1]);
    });
  }

  logger.info('************ properties dropped:', _.keys(propertiesDropped).join(', '));
}

function writeFailedEventToDisk(line) {
  fs.appendFile(failedEventsFh, line + '\n', function(err) {
    if (err) logger.error({err: err}, 'failed to write error to disk');
  });
}

var sigIntCaughtCount = 0;
process.on('SIGINT', function() {
  logger.info('SIGINT caught: quitting');
  printAndStop();
  if (++sigIntCaughtCount > 1) {
    process.exit();
  }
});

process.on('SIGUSR1', function() {
  logger.info('SIGINT caught: printing failed events');
  printFailedEvents();
});
