'use strict';

angular.module('kifi.invite.connectionCard', [])


.directive('kfConnectionCard', [/*dependencies go here*/ function(/* and here */){
  return {
    scope: {
      'friend': '&'
    },
    replace: true,
    restrict: 'A',
    templateUrl: 'invite/connectionCard.tpl.html',
    link: function (scope/*, element, attrs*/) {
      var fakeFriendName = "Arthur Dent " + scope.friend();
      scope.mainImage = "http://lorempixel.com/64/64/people";
      scope.mainLabel = fakeFriendName;
      scope.bylineIcon = "http://lorempixel.com/10/10/";
      scope.connectionId = "foobar";
      scope.hidden = false;
      scope.action = function(connectionId) {
        alert("Inviting:" + fakeFriendName);
      }
      scope.closeAction = function() {
        scope.hidden = true;
      }
      if (scope.friend()>10) {
        scope.invited = true;
        scope.actionText = "Resend";
        scope.byline = "Invited 42 days ago"
      } else {
        scope.invited = false;
        scope.byline = "Heart Of Gold";
        scope.actionText = "Add";
      }
    }
  }
}])
