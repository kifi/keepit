'use strict';

angular.module('kifi')

.factory('directiveProxyService', [
  '$compile', '$parse',
  function ($compile, $parse) {
    return function (target, scope, element, attrs, ignoreAttrs) {
      var targetGetter = $parse(target);
      target = targetGetter(scope);

      var forward = angular.element('<div>').attr(target, '');
      
      /* Move attributes over */
      var $attr = attrs.$attr;
      attrs = _.omit(attrs, ignoreAttrs || []);
      attrs = _.omit(attrs, ['class', 'id']);
      attrs = _.omit(attrs, function (val, key) {
        return key.charAt(0) === '$';
      });

      _.each(attrs, function (val, key) {
        element.removeAttr($attr[key]);
        forward.attr($attr[key], val);
      });

      $compile(forward)(scope);
      element.append(forward);
      return forward;
    };
  }
])

.directive('proxy', [
  'directiveProxyService',
  function (directiveProxyService) {
    return {
      restrict: 'A',
      terminal: true,
      priority: 1000000,
      replace: true,
      template: '<span></span>',
      link: function (scope, element, attrs) {
        directiveProxyService(attrs.proxy, scope, element, attrs, ['proxy']);
      }
    };
  }
]);
