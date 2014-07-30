module.exports.config = {
  // Use phantomjs for headless testing.
  capabilities: {
    browserName: 'chrome'
  },


  // Locally, set protractor.kifi.com to point to where the local server
  // is running (usually localhost).
  baseUrl: 'http://protractor.kifi.com:8080/'
};
