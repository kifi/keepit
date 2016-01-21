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

    function getTransitionDuration(element, tries) {
      tries = (typeof tries === 'undefined' ? 0 : tries);

      var style = getComputedStyle(element);
      if (style) {
        var stringSeconds = style.transitionDuration.slice(0, -1);
        var timeoutDuration = parseFloat(stringSeconds) * 1000;
        return timeoutDuration;
      } else if (tries < 5) {
        return getTransitionDuration(element, tries + 1);
      } else {
        return 100; // because why not
      }
    }

    function unique(array) {
      var seen = {};
      return array.filter(function(item) {
        return seen.hasOwnProperty(item) ? false : (seen[item] = true);
      });
    }

    function getSingleTransitionProperties(element, pseudo, tries) {
      tries = (typeof tries === 'undefined' ? 0 : tries);

      var commaSeparatedRe = /[, ]+/g;
      var style = getComputedStyle(element, pseudo);
      if (style) {
        return style.transitionProperty.split(commaSeparatedRe);
      } else if (tries < 5) {
        return getSingleTransitionProperties(element, pseudo, tries + 1);
      } else {
        return [];
      }
    }

    function getTransitionProperties(element) {
      var elementTransitionProperties = getSingleTransitionProperties(element);
      var beforeTransitionProperties = getSingleTransitionProperties(element, ':before');
      var afterTransitionProperties = getSingleTransitionProperties(element, ':after');
      var allProperties = elementTransitionProperties.concat(beforeTransitionProperties).concat(afterTransitionProperties);

      return unique(allProperties);
    }

    function getTransitionEvent(element, property, duration) {
      return new TransitionEvent('transitionend', {
        target: element,
        propertyName: property,
        elapsedTime: duration
      });
    }

    function getTransitionEndFn(element, fn) {
      var duration = getTransitionDuration(element);
      var transitionEndEvent = {
        target: element,
        type: 'transitionend',
        originalEvent: null
      };

      setTimeout(function () {
        var properties = getTransitionProperties(element);
        properties.forEach(function (property) {
          transitionEndEvent.originalEvent = getTransitionEvent(element, property, duration);
          fn.call(element, transitionEndEvent);
        });
      }, duration);

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
