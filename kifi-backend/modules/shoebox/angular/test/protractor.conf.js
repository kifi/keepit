exports.config = {
  seleniumAddress: 'http://localhost:4444/wd/hub',
  
  baseUrl: 'http://localhost:8080/',

  // Location of tests.
  specs: [
    './e2e/**/*.spec.js'
  ],

  // Browsers to run tests on.
  multiCapabilities: [
    {
      name: 'Firefox',
      browserName: 'firefox'
    },
    {
      name: 'Chrome',
      browserName: 'chrome'
    }
  ]
};
