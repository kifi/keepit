'use strict';

angular.module('kifi.scrollbar', [])

.factory('scrollbar', [
  '$document',
  function ($document) {

    var width = null;

    function calcScrollBarWidth() {
      // http://stackoverflow.com/questions/986937/how-can-i-get-the-browsers-scrollbar-sizes
      var document = $document[0];

      var inner = document.createElement('p');
      inner.style.width = '100%';
      inner.style.height = '200px';

      var outer = document.createElement('div');
      outer.style.position = 'absolute';
      outer.style.top = '0px';
      outer.style.left = '0px';
      outer.style.visibility = 'hidden';
      outer.style.width = '200px';
      outer.style.height = '150px';
      outer.style.overflow = 'hidden';

      outer.appendChild(inner);

      document.body.appendChild(outer);

      var w1 = inner.offsetWidth;
      outer.style.overflow = 'scroll';

      var w2 = inner.offsetWidth;
      if (w1 === w2) {
        w2 = outer.clientWidth;
      }

      document.body.removeChild(outer);

      return w1 - w2;
    }

    var antiWidth = null
    //var $ = angular.element;

    function scrollbarSize() {
      /**
       * Overriding for now - seems like it doesn't work in Firefox anymore
       * - the parent of 'antiscroll-inner' should have 'overflow: hidden'
       * - children of 'antiscroll-inner' should have 'width: calc(100% - 30px)'
       * 
       * Known issue:
       * - the scrollbar may remain in the 'thin' state all the time
       */
      return 30;

      /*var div = $(
          '<div class="antiscroll-inner" style="width:50px;height:50px;overflow-y:scroll;' +
          'position:absolute;top:-200px;left:-200px;"><div style="height:100px;width:100%"/>' +
          '</div>'
      );

      $('body').append(div);
      var w1 = $(div).innerWidth();
      var w2 = $('div', div).innerWidth();
      $(div).remove();

      return w1 - w2;*/
    }

    return {
      getWidth: function () {
        if (width == null) {
          width = calcScrollBarWidth();
        }
        return width;
      },
      getAntiscrollWidth: function () {
        if (antiWidth == null) {
          antiWidth = scrollbarSize();
        }
        return antiWidth;
      }
    };
  }
]);
