'use strict';

angular.module('kifi')

.directive('kfQuickKeep', [
  'util', 'keepActionService', 'installService', 'libraryService', 'profileService',
  'modalService', '$rootScope', 'LIB_PERMISSION',
  function (util, keepActionService, installService, libraryService, profileService,
            modalService, $rootScope, LIB_PERMISSION) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '='
      },
      templateUrl: 'libraries/quickKeep.tpl.html',
      link: function (scope) {

        scope.quickKeep = {};

        scope.hasAddKeepPermission = function () {
          return scope.library && scope.library.permissions.indexOf(LIB_PERMISSION.ADD_KEEPS) !== -1;
        };

        scope.doQuickKeep = function () {
          if (profileService.shouldBeWindingDown()) {
            modalService.showWindingDownModal();
          } else {
            var url = (scope.quickKeep.url) || '';
            scope.quickKeep.quickCheck = true;
            libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedQuickKeep' });
            url = sanitizeUrl(url);
            if (url && util.validateUrl(url)) {
              keepUrls([url]);
            } else {
              scope.quickKeep.invalidUrl = true;
            }
          }
        };

        scope.doQuickKeeps = function () {
          if (profileService.shouldBeWindingDown()) {
            modalService.showWindingDownModal();
          } else {
            var urls = scope.quickKeep.urls.split('\n');
            var sanitized = urls.map(sanitizeUrl).filter(function (e) { return e.length > 1; });
            var validated = sanitized.filter(util.validateUrl);
            var invalid = sanitized.filter(function (a) { return !util.validateUrl(a); });
            keepUrls(validated);
            var modalScope = scope.$new();
            modalScope.numKeepsProcessed = validated.length;
            modalScope.invalid = invalid;
            modalService.open({
              template: 'libraries/quickKeepsConfirmModal.tpl.html',
              scope: modalScope
            });
          }
        };

        function keepUrls(urls) {
          var urlObjs = urls.map(function (e) { return {url: e}; });
          return keepActionService.keepToLibrary(urlObjs, scope.library.id).then(function (result) {
            scope.quickKeep = {};
            if (result.failures && result.failures.length) {
              modalService.openGenericErrorModal();
            } else {
              libraryService.fetchLibraryInfos(true);
              var allKeeps = result.keeps.slice().concat(result.alreadyKept.slice());
              allKeeps.map(function (keep) {
                return keepActionService.fetchFullKeepInfo(keep).then(function (k) {
                  $rootScope.$emit('keepAdded', [k], scope.library);
                });
              });
            }
          })['catch'](modalService.openGenericErrorModal);
        }

        scope.openMultiKeepModal = function () {
          modalService.open({
            template: 'libraries/quickKeepsModal.tpl.html',
            scope: scope
          });
        };

        scope.checkQuickKeepUrl = function () {
          var url = (scope.quickKeep.url) || '';
          if (!scope.quickKeep.quickCheck) {
            return;
          }
          if (url && util.validateUrl(url) || url==='') {
            scope.quickKeep.invalidUrl = false;
          } else {
            scope.quickKeep.invalidUrl = true;
          }
        };

        scope.triggerExtensionInstall = installService.triggerInstall;

        scope.canInstallExtension = installService.canInstall;

        function sanitizeUrl(url) {
          var regex = /^https?:\/\//;
          if (!regex.test(url)) {
            return 'http://' + url;
          } else {
            return url;
          }
        }
      }
    };
  }
]);
