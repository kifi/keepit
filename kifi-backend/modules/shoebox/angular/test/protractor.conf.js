module.exports.config = {
  // Use phantomjs for headless testing.
  capabilities: {
    browserName: 'chrome',
    shardTestFiles: true,
    maxInstances: 3
  },

  // Locally, set protractor.kifi.com to point to where the local server
  // is running (usually localhost).
  baseUrl: 'http://protractor.kifi.com:9080/',

  // Increase protractor timeout for page synchronization to 15 seconds.
  // (default is 11 seconds)
  // See: https://github.com/angular/protractor/blob/master/docs/timeouts.md
  allScriptsTimeout: 20000
};
