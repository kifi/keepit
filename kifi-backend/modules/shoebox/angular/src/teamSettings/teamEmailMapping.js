'use strict';

angular.module('kifi')

.directive('kfTeamEmailMapping', [
  '$stateParams', 'profileService', 'orgProfileService', 'modalService',
  'ORG_PERMISSION', 'ORG_SETTING_VALUE',
  function ($stateParams, profileService, orgProfileService, modalService,
            ORG_PERMISSION, ORG_SETTING_VALUE) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        getOrg: '&org',
        getViewer: '&viewer'
      },
      templateUrl: 'teamSettings/teamEmailMapping.tpl.html',
      link: function ($scope) {
        $scope.enabled = (
          $scope.getViewer().permissions.indexOf(ORG_PERMISSION.MANAGE_PLAN) !== -1 &&
          $scope.getOrg().config.settings.join_by_verifying.setting !== ORG_SETTING_VALUE.DISABLED
        );
        $scope.open = $scope.enabled && !!$stateParams.openDomains; // initial
        $scope.me = profileService.me;
        $scope.emailDomains = null;
        $scope.verificationMessage = '';
        $scope.model = {
          email: ''
        };

        function getEmailDomain(address) {
          return address.slice(address.lastIndexOf('@') + 1);
        }

        function getVerifyMessage(address) {
          return (
            'A verification email was sent to ' + address + '.' +
            ' Click the verification link in the email ' +
            'to claim your team\'s domain.'
          );
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
          var address = email.address;
          var domain = address.slice(address.lastIndexOf('@') + 1);

          if (!email.isVerified) {
            $scope.verificationMessage = getVerifyMessage(email.address);
          } else {
            $scope.verificationMessage = '';
          }

          return orgProfileService
          .addOrgDomain($scope.getOrg().id, domain)
          .then(function (domainData) {
            profileService.fetchMe();
            $scope.emailDomains.push(domainData.domain);
          })
          ['catch'](function () {
            $scope.verificationMessage = '';
            modalService.openGenericErrorModal();
          });
        };

        $scope.removeOrgDomain = function (email, index) {
          orgProfileService
          .removeOrgDomain($scope.getOrg().id, email)
          .then(function () {
            $scope.emailDomains.splice(index, 1);
          })
          ['catch'](modalService.openGenericErrorModal);
        };

        $scope.addUnverifiedOrgDomain = function (email) {
          return orgProfileService
          .addDomainAfterVerification($scope.getOrg().id, email.address)
          .then(function () {
            $scope.verificationMessage = getVerifyMessage(email.address);
            return profileService.resendVerificationEmail(email.address);
          })
          ['catch'](modalService.openGenericErrorModal);
        };

        $scope.addEmailAccount = function (address) {
          return profileService
          .addEmailAccount(address)
          .then(function () {
            $scope.verificationMessage = getVerifyMessage(address);
            $scope.model.email = '';

            // stub it instead of looking for it in the me object
            $scope.addOrgDomain({
               address: address,
               isVerified: false
             });
          })
          ['catch'](function (err) {
            $scope.verificationMessage = err;
          });
        };

        orgProfileService
        .getOrgDomains($scope.getOrg().id)
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
