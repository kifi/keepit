'use strict';

angular.module('kifi.layout.main', ['kifi.undo'])

.controller('MainCtrl', [
  '$scope', '$element', '$window', '$location', '$timeout', '$rootElement', 'undoService', 'keyIndices',
  function ($scope, $element, $window, $location, $timeout, $rootElement, undoService, keyIndices) {

    $scope.search = {};

    $scope.isEmpty = function () {
      return !$scope.search.text;
    };

    $scope.onKeydown = function (e) {
      if (e.keyCode === keyIndices.KEY_ESC) {
        $scope.clear();
      } else if (e.keyCode === keyIndices.KEY_ENTER) {
        performSearch();
      }
    };

    $scope.onFocus = function () {
      $scope.focus = true;
    };

    $scope.onBlur = function () {
      $scope.focus = false;
    };

    $scope.clear = function () {
      $scope.search.text = '';
    };

    function performSearch() {
      var text = $scope.search.text || '';
      text = _.str.trim(text);

      if (text) {
        $location.path('/find').search('q', text);
      }
      else {
        $location.path('/').search('');
      }

      // hacky solution to url event not getting fired
      $timeout(function () {
        $scope.$apply();
      });
    }

    $scope.onChange = _.debounce(performSearch, 350);

    $scope.$on('$routeChangeSuccess', function (event, current, previous) {
      if (previous && current && previous.controller === 'SearchCtrl' && current.controller !== 'SearchCtrl') {
        $scope.search.text = '';
      }
    });

    $scope.undo = undoService;

    var updateHeight = _.throttle(function () {
      $element.css('height', $window.innerHeight + 'px');
    }, 100);
    angular.element($window).resize(updateHeight);

    $timeout(updateHeight);

    if (/^Mac/.test($window.navigator.platform)) {
      $rootElement.find('body').addClass('mac');
    }
  }
]);
