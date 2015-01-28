module.exports.config = {
  // Use phantomjs for headless testing.
  capabilities: {
    browserName: 'chrome'
  },

  // Locally, set protractor.kifi.com to point to where the local server
  // is running (usually localhost).
  baseUrl: 'http://protractor.kifi.com:9080/',

  // Increase protractor timeout for page synchronization (default is 11s)
  // https://github.com/angular/protractor/blob/master/docs/timeouts.md
  allScriptsTimeout: 60000
};
