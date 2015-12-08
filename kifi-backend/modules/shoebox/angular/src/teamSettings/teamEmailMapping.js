'use strict';

angular.module('kifi')

.directive('kfTeamEmailMapping', [
  '$stateParams', 'profileService', 'net', 'orgProfileService', 'modalService',
  'ORG_PERMISSION', 'ORG_SETTING_VALUE',
  function ($stateParams, profileService, net, orgProfileService, modalService,
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
        $scope.visible = $scope.getViewer().permissions.indexOf(ORG_PERMISSION.MANAGE_PLAN) !== -1;
        $scope.enabled = $scope.visible && $scope.getOrg().config.settings.join_by_verifying.setting !== ORG_SETTING_VALUE.DISABLED;
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
            return $scope.emailCanBeClaimed(email);
          });
        };

        $scope.emailCanBeClaimed = function (email) {
          return !email.isFreeMail && ($scope.emailDomains || []).indexOf(getEmailDomain(email.address)) === -1;
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

        $scope.addNewEmailAccount = function (address) {
          return net
          .getEmailInfo({ email: address })
          .then(function (response) {
            var emailInfo = response.data;

            if (emailInfo.status === 'available') {
              return profileService
              .addEmailAccount(address);
            } else if (emailInfo.isVerified === false) {
              return; // go to the next piece of the promise chain
            } else {
              throw 'already_added';
            }
          })
          .then(function () {
            $scope.verificationMessage = getVerifyMessage(address);
            $scope.model.email = '';

            // stub it instead of looking for it in the me object
            $scope.addUnverifiedOrgDomain({
              address: address,
              isVerified: false
            });
          })
          ['catch'](function (err) {
            var message;
            if (typeof err === 'string') {
              switch(err) {
                case 'already_added':
                  message = 'You have already added and verified this email.';
                  break;
                case 'email_belongs_to_other_user':
                  message = 'Another use already owns this email address.';
                  break;
              }
            }
            $scope.verificationMessage = message || 'Something went wrong. Try again?';
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
