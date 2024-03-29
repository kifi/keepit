'use strict';

angular.module('kifi')

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

    return {
      getWidth: function () {
        if (width == null) {
          width = calcScrollBarWidth();
        }
        return width;
      }
    };
  }
]);
