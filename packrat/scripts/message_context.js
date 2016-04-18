// @require styles/keeper/message_context.css
// @require scripts/html/keeper/kifi_mustache_tags.js
// @require scripts/html/keeper/message_context.js
// @require scripts/lib/q.min.js
// @require scripts/lib/jquery.js
// @require scripts/progress.js
// @require scripts/formatting.js
// @require scripts/render.js

/**
 * --------------------------
 *  Message Context
 * --------------------------
 *
 * Message Participants is an UI component that manages
 * the related page content of the keepscussion
 */

k.messageContext = k.messageContext || (function ($) {
  'use strict';

  var portHandlers = {
  };

  api.onEnd.push(function () {
    k.messageContext.destroy();
  });

  return {
    /**
     * Whether this component is initialized
     *
     * @property {boolean}
     */
    initialized: false,

    /**
     * Parent UI Component. e.g. Message Header
     *
     * @property {Object}
     */
    parent: null,

    /**
     * Initializes a Message Participants.
     */
    init: function () {
      this.initialized = true;
      api.port.on(portHandlers);

      var $view = this.get$();
      var keep = this.getKeep();
      this.initEvents();

      $view.data({
        saved: {
          title: keep.title
        },
        saving: {}
      });
    },

    /**
     * Initializes event listeners.
     */
    initEvents: function () {
      var debouncedSaveKeepTitleIfChanged = _.debounce(saveKeepTitleIfChanged, 1500);
      var $view = this.get$();
      var self = this;

      this.handleEsc = this.escHandler.bind(this);
      $(document).data('esc').add(this.handeEsc);

      $view
      .on('input', '.kifi-message-context-controls-title', $.proxy(debouncedSaveKeepTitleIfChanged, this, $view))
      .on('keydown', '.kifi-message-context-controls-title', function (e) {
        switch (e.keyCode) {
          case 27:
            this.value = $view.data().saved.title;
            // intentional fall-through
          case 13:
            self.toggleEditTitle(false);
            break;
          default:
            // noop
        }
      })
      .on('blur', '.kifi-message-context-controls-title', function () {
        if (!this.value.trim()) {
          this.value = $view.data().saved.title;
          saveKeepTitleIfChanged.call(this, $view);
        }
      });
    },

    escHandler: function () {
      this.get$().find('.kifi-message-context-controls-title').blur();
    },

    /**
     * Renders a UI component given the component name.
     *
     * @param {string} name - UI component name
     *
     * @return {string} A rendered html
     */
    render: function (name) {
      switch (name) {
      case 'button':
        return this.renderButton();
      case 'content':
        return this.renderContent();
      //case 'option':
      }
    },

    getKeep: function () {
      return this.parent.keep;
    },

    getUrlParts: function (url) {
      var a = document.createElement('a');
      a.href = url;
      var o = {};
      var props = [ 'protocol', 'hostname', 'port', 'pathname', 'search', 'hash', 'host' ];
      props.forEach(function (k) {
        o[k] = a[k];
      });

      return o;
    },

    /**
     * Returns the current state object excluding UI states.
     *
     * @return {Object} A state object
     */
    getView: function () {
      var keep = this.getKeep();
      var favicon = document.querySelector('[rel~="icon"],[type="image/x-icon"]');
      var alternateImage = favicon && favicon.getAttribute('href');

      return {
        image: (keep.image && keep.image.url) || alternateImage,
        title: keep.title,
        location: this.getUrlParts(keep.url).hostname
      };
    },
    /**
     * Renders and returns html for participants container.
     *
     * @return {string} participants html
     */
    renderContent: function () {
      return $(k.render('html/keeper/message_context', this.getView()));
    },

    /**
     * Renders and returns a 'Add Participants' button.
     *
     * @return {string} Add participant icon html
     */
    renderButton: function () {
      return ''; //$(k.render('html/keeper/message_participant_icon', this.getView(), partials));
    },

    /**
     * Finds and returns a jQuery wrapper object for the given selector.
     *
     * @param {string} [selector] a optional selector
     *
     * @return {jQuery} A jQuery wrapper object
     */
    get$: function (selector) {
      return this.parent.find(selector || '.kifi-message-context');
    },

    /**
     * Returns a jQuery wrapper object for the parent module.
     *
     * @return {jQuery} A jQuery wrapper object
     */
    getParent$: function () {
      return this.parent.$el;
    },

    toggleEditTitle: function (setFocused) {
      var $edit = this.get$().find('.kifi-message-context-controls-title');
      if (setFocused) {
        $edit.focus().select();
      } else {
        $edit.blur();
      }
    },

    refresh: function () {
      this.get$().empty().append(this.renderContent());
    },

    /**
     * It removes all event listeners and caches to elements.
     */
    destroy: function () {
      this.initialized = false;
      this.parent = null;

      api.port.off(portHandlers);

      $(document).data('esc').remove(this.handleEsc);

      // .remove() not called on jQuery elements because it would disrupt a farewell transition
      // an ancestor should call it later (e.g. by being removed itself)

      this.$el = null;
    }
  };

  function saveKeepTitleIfChanged($view) {
    var keep = this.getKeep();
    var keepId = keep.id;

    if (!$view) {
      return;  // already removed, no data
    }

    var deferred = Q.defer();
    var input = $view.find('.kifi-message-context-controls-title')[0];
    var data = $view.data();
    var val = input.value.trim();

    k.progress.emptyAndShow($view, deferred.promise);

    if (val && val !== data['title' in data.saving ? 'saving' : 'saved'].title) {
      data.saving.title = val;
      api.port.emit('save_keepscussion_title', {keepId: keepId, newTitle: val}, function (success) {
        if (data.saving.title === val) {
          delete data.saving.title;
        }

        if (success) {
          keep.title = data.saved.title = val;
          deferred.resolve();
        } else {
          deferred.reject();
        }
      });
    }
  }
})(jQuery, this);
