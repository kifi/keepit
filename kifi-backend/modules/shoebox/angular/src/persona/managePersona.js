'use strict';

angular.module('kifi')

.directive('kfManagePersona', [ 'userPersonaActionService', 'routeService', '$analytics', 'util', 'modalService', '$rootScope', 'libraryService',
  function (userPersonaActionService, routeService, $analytics, util, modalService, $rootScope, libraryService) {
    return {
      restrict: 'A',
      scope: {
        closeAction: '='
      },
      templateUrl: 'persona/managePersona.tpl.html',
      link: function (scope, element) {
        //
        // Scope data.
        //
        scope.selectedPersonaIds = [];
        scope.closeText = element.is('#kf-modal *') ? 'Update interests' : 'Done';

        //
        // Scope methods.
        //
        scope.selectPersona = function(persona) {
          persona.selected = true;
          scope.selectedPersonaIds.push(persona.id);
          userPersonaActionService.addPersona(persona.id);

          // to Camel Case Analytics
          var personaAction = ['selected','persona'].concat(persona.id.split('_'));
          $analytics.eventTrack('user_clicked_page', {
            type: persona.id,
            action: util.toCamelCase(personaAction)
          });
        };

        scope.unselectPersona = function(persona) {
          persona.selected = false;
          _.pull(scope.selectedPersonaIds, persona.id);
          userPersonaActionService.removePersona(persona.id);

          // to Camel Case Analytics
          var personaAction = ['unselected','persona'].concat(persona.id.split('_'));
          $analytics.eventTrack('user_clicked_page', {
            type: persona.id,
            action: util.toCamelCase(personaAction)
          });
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
            if (_.isFunction(scope.closeAction)) {
              scope.closeAction();
            } else if (element.is('#kf-modal *')) {
              $rootScope.$emit('refreshRecos');
              modalService.close();
            }
            libraryService.fetchLibrarySummaries(true);
            $analytics.eventTrack('user_clicked_page', {action: 'closed'});
          }
        };

        //
        // On link.
        //
        // call for List of Personas
        // todo (aaron): put this asset CDN base url into env?
        var cdnBase = 'https://d1dwdv9wd966qu.cloudfront.net/'; // for static assets (icons)
        userPersonaActionService.getPersonas().then(function (data) {
          _.map(data.personas, function(p) {
            p.iconSrc = cdnBase + p.iconPath;
            p.activeIconSrc = cdnBase + p.activeIconPath;
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
