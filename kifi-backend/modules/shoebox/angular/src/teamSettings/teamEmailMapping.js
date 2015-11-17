'use strict';

angular.module('kifi')

.directive('kfTeamEmailMapping', [
  '$compile', 'profileService', 'billingService', 'modalService',
  function ($compile, profileService, billingService, modalService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        getOrgId: '&orgId'
      },
      templateUrl: 'teamSettings/teamEmailMapping.tpl.html',
      link: function ($scope) {
        $scope.open = false;
        $scope.me = profileService.me;
        $scope.emailDomains = null;
        $scope.model = {
          email: ''
        };

        function getEmailDomain(address) {
          return address.slice(address.lastIndexOf('@') + 1);
        }

        $scope.toggleOpen = function () {
          $scope.open = !$scope.open;
        };

        $scope.getClaimableEmails = function (emails) {
          return emails.filter(function (email) {
            return $scope.emailCanBeClaimed(email.address);
          });
        };

        $scope.emailCanBeClaimed = function (email) {
          return !email.isFreeEmail && ($scope.emailDomains || []).indexOf(getEmailDomain(email)) === -1;
        };

        $scope.resendVerificationEmail = function (email) {
          $scope.emailForVerification = email;
          modalService.open({
            template: 'profile/emailResendVerificationModal.tpl.html',
            scope: $scope
          });

          profileService.resendVerificationEmail(email);
        };

        $scope.addOrgDomain = function (email) {
          var domain = email.slice(email.lastIndexOf('@') + 1);

          billingService
          .addOrgDomain($scope.getOrgId(), domain)
          .then(function (domainData) {
            profileService.fetchMe();
            $scope.emailDomains.push(domainData.domain);
          })
          ['catch'](modalService.openGenericErrorModal);
        };

        $scope.removeOrgDomain = function (email, index) {
          billingService
          .removeOrgDomain($scope.getOrgId(), email)
          .then(function () {
            $scope.emailDomains.splice(index, 1);
          })
          ['catch'](modalService.openGenericErrorModal);
        };

        billingService
        .getOrgDomains($scope.getOrgId())
        .then(function (emailDomainData) {
          $scope.emailDomains = emailDomainData;
        })
        ['catch'](function () {
          $scope.emailDomains = null;
        });
      }
    };
  }
]);
