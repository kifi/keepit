'use strict';

describe('kifi.layout.rightCol', function () {

  var $scope,
    $element,
    $timeout,
    $window,
    ctrl;

  beforeEach(module('kifi', 'kifi.layout.rightCol'));

  beforeEach(inject(function (_$controller_, _$rootScope_, _$timeout_, _$window_) {
    $scope = _$rootScope_.$new();
    $timeout = _$timeout_;
    $window = _$window_;
    $element = {
      css: jasmine.createSpy('css')
    };
    ctrl = _$controller_('RightColCtrl', {
      $scope: $scope,
      $element: $element
    });
  }));

});
