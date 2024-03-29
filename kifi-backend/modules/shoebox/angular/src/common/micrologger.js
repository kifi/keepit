'use strict';

// Usage instructions: https://gist.github.com/adamjgrant/a166ac9311dba2f783bc

angular.module('kifi')

.factory('ml', [
  '$window', '$timeout', '$exceptionHandler', '$log',
  function($window, $timeout, $exceptionHandler, $log){
    var micrologger = {
      specs: {},
      Assert: function(string, timeout, spec) {
        this.string = string;
        this.timeout = timeout;
        this.spec = spec;
        this.fail = $timeout(function() {
          return micrologger.fail(this.string);
        }.bind(this), this.timeout);
      },
      Expect: function(string, condition) {
        this.string = string;
        this.condition = condition;
      },
      Spec: function(tests) {
        this.tests = [];
        this.tests = this.tests.concat(tests);
      },
      pass: function(message) {
        // Only fires if window.ml.pass() is defined.
        if ($window.ml && $window.ml.pass) {
          $window.ml.pass(function() {
            $log.info('%c[PASS]: ' + message, 'color: green');
          });
        }
      },
      fail: function(message, args) {
        $exceptionHandler('[FAIL]: ' + message + ', ' + $window.JSON.stringify(args));
      }
    };

    micrologger.Assert.prototype.respond = function() {
      $timeout.cancel(this.fail);
      micrologger.pass(this.string);
    };

    micrologger.Expect.prototype.respond = function() {
      var args = Array.prototype.slice.call(arguments);
      try {
        if (this.condition.apply(null, args)) {
          return micrologger.pass(this.string);
        }
        else {
          return micrologger.fail(this.string, args);
        }
      }
      catch (e) { return micrologger.fail(this.string, args); }
    };

    micrologger.Spec.prototype.respond = function(args) {
      this.tests.forEach(function(test, index) {
        test.respond(args[index]);
      });
    };

    return micrologger;
  }
]);
