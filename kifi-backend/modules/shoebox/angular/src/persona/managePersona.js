'use strict';

angular.module('kifi')

.directive('kfManagePersona', [ 'userPersonaActionService', 'routeService', '$analytics', 'util',
  function (userPersonaActionService, routeService, $analytics, util) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'persona/managePersona.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {
        //
        // Scope data.
        //
        scope.selectedPersonaIds = [];
        scope.finishText = scope.modalData && scope.modalData.finishText ? scope.modalData.finishText : 'Update interests';

        //
        // Scope methods.
        //
        scope.selectPersona = function(persona) {
          persona.selected = true;
          persona.currentIconSrc = persona.activeIconSrc;
          scope.selectedPersonaIds.push(persona.id);
          userPersonaActionService.addPersona(persona.id);

          // to Camel Case Analytics
          var personaAction = util.upperCaseFirstLetterOfWords(persona.id.split('_')).join('');
          $analytics.eventTrack('user_clicked_page', {type: persona.id, action: 'selectedPersona' + personaAction});
        };

        scope.unselectPersona = function(persona) {
          persona.selected = false;
          persona.currentIconSrc = persona.iconSrc;
          _.pull(scope.selectedPersonaIds, persona.id);
          userPersonaActionService.removePersona(persona.id);

          // to Camel Case Analytics
          var personaAction = util.upperCaseFirstLetterOfWords(persona.id.split('_')).join('');
          $analytics.eventTrack('user_clicked_page', {type: persona.id, action: 'unselectedPersona' + personaAction});
        };

        scope.toggleSelect = function(persona) {
          if (persona.selected) {
            scope.unselectPersona(persona);
          } else {
            scope.selectPersona(persona);
          }
        };

        scope.close = function() {
          if (scope.selectedPersonaIds.length > 0) {
            var closeFunc = scope.modalData ? scope.modalData.onClose : undefined;
            kfModalCtrl.close(closeFunc, true);
          }
          $analytics.eventTrack('user_clicked_page', {action: 'closed'});
        };

        //
        // On link.
        //
        // call for List of Personas
        var cdnBase = routeService.cdnAssetBase;
        userPersonaActionService.getPersonas().then(function (data) {
          _.map(data.personas, function(p) {
            p.displayName = p.displayName.toUpperCase();
            p.iconSrc = cdnBase + p.iconPath;
            p.activeIconSrc = cdnBase + p.activeIconPath;
            p.currentIconSrc = p.selected ? p.activeIconSrc : p.iconSrc;
            if (p.selected) {
              scope.selectedPersonaIds.push(p.id);
            }
          });
          scope.allPersonas = data.personas;
        });

      }
    };
  }
]);
