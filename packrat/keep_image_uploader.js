(function () {
  'use strict';

  var binaryImagesByStatusPath = {};

  this.keepImageUploader = {
    checkStatus: function (statusPath) {
      ajax('GET', statusPath, function (resp) {
        // if upload is necessary
        //    when we have bytes, upload them to path in response
        // else
        //    when we have bytes, discard them
      }, function (resp) {

      });
    }
  };
}.call(this.exports || this));
