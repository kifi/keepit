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

    function getTransitionDuration(element) {
      var style = getComputedStyle(element);
      var stringSeconds = style.transitionDuration.slice(0, -1);
      var timeoutDuration = parseFloat(stringSeconds) * 1000;
      return timeoutDuration;
    }

    function getTransitionProperty(element) {
      var style = getComputedStyle(element);
      return style.transitionProperty;
    }

    function getTransitionEndFn(element, fn) {
      var self = element;
      var transitionDuration = getTransitionDuration(element);

      var transitionEndEvent = {
        target: element,
        type: 'transitionend',
        originalEvent: new TransitionEvent('transitionend', {
          target: element,
          propertyName: getTransitionProperty(element),
          elapsedTime: transitionDuration
        })
      };

      setTimeout(function () {
        fn.call(self, transitionEndEvent);
      }, transitionDuration + 50);

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
