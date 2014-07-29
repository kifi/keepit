exports.config = {
  // Use phantomjs for headless testing.
  capabilities: {
    browserName: 'phantomJS'
  },

  // Address for phantom GhostDriver.
  // Start phantom with: "phantomjs --webdriver=9515"
  seleniumAddress: 'http://localhost:9515',
  
  // Locally, set protractor.kifi.com to point to where the local server
  // is running (usually localhost).
  baseUrl: 'http://protractor.kifi.com:8080/',

  // Location of tests.
  specs: [
    './e2e/**/*.spec.js'
  ]
};
