// @require scripts/lib/q.min.js

k.request = k.request || function (name, data, errorMsg) {
  'use strict';
  var deferred = Q.defer();
  api.port.emit(name, data, function (result) {
    log(name + '.result', result);
    if (result && result.success) {
      deferred.resolve(result.response);
    } else {
      deferred.reject(new Error(errorMsg));
    }
  });
  return deferred.promise;
};
