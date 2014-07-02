'use strict';

module.exports = function (config) {
  config.set({
    basePath: '../',
    frameworks: ['jasmine'],
    files: [
      'dist/lib.js',
      'lib/angular-mocks/angular-mocks.js',
      'dist/kifi-tpl.js',
      'src/**/*.js',
      'test/**/*.js'
    ],
    reporters: ['dots'],
    colors: true,
    logLevel: config.LOG_INFO,
    autoWatch: true,
    browsers: ['PhantomJS'],
    captureTimeout: 60000,
    singleRun: true
  });
};
