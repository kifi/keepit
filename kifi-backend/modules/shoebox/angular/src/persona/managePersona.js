'use strict';

angular.module('kifi')

.directive('kfManagePersona', [
  function () {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'persona/managePersona.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {
        //
        // Scope data.
        //
        scope.numSelected = 0;

        //
        // Scope methods.
        //
        scope.selectPersona = function(persona) {
          persona.selected = true;
          persona.iconSrc = persona.activeIconPath;
          scope.numSelected++;
          // call add persona route
        };

        scope.unselectPersona = function(persona) {
          persona.selected = false;
          persona.iconSrc = persona.iconPath;
          scope.numSelected--;
          // call remove persona route
        };

        scope.toggleSelect = function(persona) {
          if (persona.selected) {
            scope.unselectPersona(persona);
          } else {
            scope.selectPersona(persona);
          }
        };

        scope.close = function() {
          if (scope.numSelected > 0) {
            kfModalCtrl.close();
            if (scope.modalData && typeof(scope.modalData.onClose) === 'function') {
              scope.modalData.onClose();
            }
          }
        };

        //
        // On link.
        //
        // call for List of Personas (with UserId or not)
        var fakeIconPath = 'http://vignette4.wikia.nocookie.net/fantendo/images/3/32/Mushroom_kingdom.png/revision/latest/scale-to-width/180?cb=20141025145114';
        //var fakeIconPath = 'http://images.wikia.com/ssb/images/archive/3/30/20100421222328!MarioSymbol.svg';
        var activeFakeIconPath = 'http://ecx.images-amazon.com/images/I/4104hONi8bL._SY300_.jpg';
        var personaList = [];
        for (var i=0; i < 15; i++) {
          var persona = { id: 'Foodie' + i.toString(),
                          displayName: 'Foodie' + i.toString(),
                          selected: false,
                          iconPath: fakeIconPath,
                          activeIconPath: activeFakeIconPath };
          persona.iconSrc = persona.iconPath;
          personaList.push(persona);
          if (persona.selected) {
            scope.numSelected++;
          }
        }
        scope.allPersonas = { personas : personaList };
      }
    };
  }
]);
