'use strict';

angular.module('antiscroll', [])

.directive('antiscroll', [
	'$timeout',
	function ($timeout) {
		function toDash(str) {
			return str.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase();
		}

		return {
			restrict: 'A',
			transclude: true,
			link: function (scope, element, attrs /*, ctrl, transclude*/ ) {
				console.log('antiscroll.link', scope, element, attrs);
				var options;
				if (attrs.antiscroll) {
					options = scope.$eval(attrs.antiscroll);
				}
				scope.scroller = element.antiscroll(options).data('antiscroll');

				scope.refreshScroll = function () {
					return $timeout(function () {
						if (scope.scroller) {
							scope.scroller.refresh();
						}
					});
				};

				scope.refreshScroll();
			},
			template: function (element, attrs) {
				console.log('antiscroll.template', this, element, attrs);
				var tmp = '<div class="antiscroll-inner"';
				if ('antiInfiniteScroll' in attrs) {
					angular.forEach(['antiInfiniteScroll', 'antiInfiniteScrollDistance', 'antiInfiniteScrollDisabled', 'antiInfiniteScrollImmediateCheck'], function (name) {
						if (name in attrs) {
							tmp += ' ' + toDash(name).substring(5) + '="' + attrs[name] + '"';
						}
					});
				}
				tmp += ' ng-transclude></div>';
				return tmp;
			}
		};
	}
]);
