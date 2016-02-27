(function ($) {
  $.fn.on = function (types, selector, data, fn, one) {
    var type, origFn;

    // Types can be a map of types/handlers
    if ( typeof types === 'object' ) {
      // ( types-Object, selector, data )
      if ( typeof selector !== 'string' ) {
        // ( types-Object, data )
        data = data || selector;
        selector = undefined;
      }
      for ( type in types ) {
        this.on( type, selector, data, types[ type ], one );
      }
      return this;
    }

    if ( data == null && fn == null ) {
      // ( types, fn )
      fn = selector;
      data = selector = undefined;
    } else if ( fn == null ) {
      if ( typeof selector === 'string' ) {
        // ( types, selector, fn )
        fn = data;
        data = undefined;
      } else {
        // ( types, data, fn )
        fn = data;
        data = selector;
        selector = undefined;
      }
    }
    if ( fn === false ) {
      fn = function () { return false; };
    } else if ( !fn ) {
      return this;
    }

    if ( one === 1 ) {
      origFn = fn;
      fn = function( event ) {
        // Can use an empty set, since event contains the info
        $().off( event );
        return origFn.apply( this, arguments );
      };
      // Use same guid so caller can remove using origFn
      fn.guid = origFn.guid || ( origFn.guid = $.guid++ );
    }

    // -- above is unedited -- //

    function computedStyle(element, pseudo) {
      if (element === document) {
        element = document.documentElement;
      }

      var savedDisplay = element.style.display;
      var cs;

      element.style.display = 'block';
      cs = getComputedStyle(element, pseudo);
      element.style.display = savedDisplay;

      return cs;
    }

    function getTransitionDuration(element) {
      var style = computedStyle(element);
      if (style) {
        var stringSeconds = style.transitionDuration.slice(0, -1);
        var timeoutDuration = parseFloat(stringSeconds) * 1000;
        return timeoutDuration;
      }
    }

    function unique(array) {
      var seen = {};
      return array.filter(function(item) {
        return seen.hasOwnProperty(item) ? false : (seen[item] = true);
      });
    }

    function getSingleTransitionProperties(element, pseudo) {
      var commaSeparatedRe = /[, ]+/g;
      var style = computedStyle(element, pseudo);

      return style.transitionProperty.split(commaSeparatedRe);
    }

    function getTransitionProperties(element) {
      var elementTransitionProperties = getSingleTransitionProperties(element);
      var beforeTransitionProperties = getSingleTransitionProperties(element, ':before');
      var afterTransitionProperties = getSingleTransitionProperties(element, ':after');
      var allProperties = elementTransitionProperties.concat(beforeTransitionProperties).concat(afterTransitionProperties);
      var uniqueProps = unique(allProperties);
      var indexOfAll = uniqueProps.indexOf('all');
      if (uniqueProps.length > 1 && indexOfAll > -1) {
        uniqueProps.splice(indexOfAll, 1);
      }
      return uniqueProps;
    }

    function getTransitionEvent(element, property, duration) {
      return new TransitionEvent('transitionend', {
        target: element,
        propertyName: property,
        elapsedTime: duration
      });
    }

    function getTransitionEndFn(element, fn) {
      var selectedElement = (selector ? element.querySelector(selector) || element : element);
      var duration = getTransitionDuration(selectedElement);
      var transitionEndEvent = {
        target: selectedElement,
        type: 'transitionend',
        originalEvent: null
      };

      setTimeout(function () {
        var properties = getTransitionProperties(selectedElement);
        properties.forEach(function (property) {
          transitionEndEvent.originalEvent = getTransitionEvent(selectedElement, property, duration);
          fn.call(selectedElement, transitionEndEvent);
        });
      }, duration + 20);

      return function () {
        // Even when Safari says it triggered transitionend, it doesn't : /
        // so we just solely rely on the setTimeout
      };
    }

    return this.each(function() {
      var fnToCall;
      if (~types.indexOf('transitionend')) {
        fnToCall = getTransitionEndFn(this, fn);
      } else {
        fnToCall = fn;
      }
      $.event.add( this, types, fnToCall, data, selector );
    });
  };
}($));
