// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/message_context.js
// @require scripts/message_participants.js
// @require scripts/message_muter.js

/**
 * ---------------------
 *    Message Header
 * ---------------------
 *
 * Message Header is an UI component that is a hub for plugins
 * related to current message context.
 */

k.messageHeader = k.messageHeader || (function ($, win) {
  'use strict';

  api.onEnd.push(function () {
    k.messageHeader.destroy();
  });

  return {
    initialized: false,
    plugins: [
      k.messageContext,
      k.messageParticipants,
      k.messageMuter
    ],
    status: null,
    threadId: null,
    participants: null,
    escHandler: null,
    onDocClick: null,

    /**
     * Renders and initializes a message header box.
     */
    init: function ($el, threadId, participants, keep) {
      if (this.initialized) {
        this.destroy();
      }
      this.threadId = threadId;
      this.participants = participants;
      this.keep = keep;
      this.initialized = true;
      this.status = {};
      this.plugins.forEach(function (plugin) {
        plugin.parent = this;
      }, this);
      this.$el = $el;
      this.render();
      this.plugins.forEach(function (plugin) {
        plugin.init();
      });
      this.initEvents();
    },

    /**
     * Initializes event listeners.
     */
    initEvents: (function () {
      function onClick(e) {
        if (this.isOptionExpanded() && !$(e.target).closest('.kifi-message-header-options').length) {
          this.hideOptions();
          e.optionsClosed = true;
        }
      }

      function addDocListeners() {
        if (this.initialized) {
          this.escHandler = this.handleEsc.bind(this);
          $(document).data('esc').add(this.escHandler);

          var onDocClick = this.onDocClick = onClick.bind(this);
          document.addEventListener('click', onDocClick, true);
        }
      }

      return function () {
        this.on('click', '.kifi-message-header-option-button', this.toggleOptions.bind(this));

        win.setTimeout(addDocListeners.bind(this));
      };
    })(),

    isOptionExpanded: function () {
      return Boolean(this.getStatus('option-expanded'));
    },

    showOptions: function () {
      this.setStatus('option-expanded', true);
      k.messageParticipants.hideAddDialog();
    },

    hideOptions: function () {
      this.setStatus('option-expanded', false);
    },

    shadePane: function () {
      if (k.pane) {
        var $who = this.$el.closest('.kifi-thread-who').addClass('kifi-active');
        k.pane.shade();
        clearTimeout($who.data('t'));
      }
    },

    unshadePane: function () {
      if (k.pane) {
        var $who = this.$el.closest('.kifi-thread-who').removeClass('kifi-active').data('t', setTimeout(function () {
          $who.css('overflow', '');
        }, 300));
        k.pane.unshade();
      }
    },

    toggleOptions: function (e) {
      if (e && e.originalEvent && e.originalEvent.optionsClosed) {
        return;
      }
      if (this.isOptionExpanded()) {
        this.hideOptions();
      }
      else {
        this.showOptions();
      }
    },

    handleEsc: function (e) {
      if (this.isOptionExpanded()) {
        this.hideOptions();
        return false;
      }
    },

    /**
     * Destroys a message header.
     * It removes all event listeners and caches to elements.
     */
    destroy: function ($el) {
      log('[message_header:destroy]', this.initialized, this.threadId, $el || '');
      if (this.initialized && (!$el || $el.is(this.$el))) {
        this.initialized = false;
        this.threadId = null;

        this.unshadePane();

        this.plugins.forEach(function (plugin) {
          plugin.destroy();
        });

        $(document).data('esc').remove(this.escHandler);
        this.escHandler = null;

        var onDocClick = this.onDocClick;
        if (onDocClick) {
          document.removeEventListener('click', onDocClick, true);
          this.onDocClick = null;
        }

        // this.$el.remove() not called because it would disrupt a farewell transition
        // parent should call it later (e.g. by being removed itself)

        this.status = null;
        this.$el = null;
        this.participants = null;
      }
    },

    /**
     * Returns whether the given element is contained inside this UI container.
     *
     * @param {HTMLElement} el - an html element
     *
     * @return {boolean} Whether the given element is contained inside this UI container.
     */
    contains: function (el) {
      var $el = this.$el;
      return $el != null && $el[0].contains(el);
    },

    find: function (sel) {
      var $el = this.$el;
      return $el ? $el.find(sel) : null;
    },

    on: function () {
      var $el = this.$el;
      return $el && $el.on.apply($el, arguments);
    },

    setStatus: function (name, isSet) {
      isSet = Boolean(isSet);
      this.$el.toggleClass('kifi-' + name, isSet);
      this.status[name] = isSet;
    },

    getStatus: function (name) {
      return Boolean(this.status[name]);
    },

    renderStatusClasses: function (status) {
      if (!status) {
        status = this.status;
      }
      return Object.keys(status).reduce(function (list, name) {
        if (status[name]) {
          list.push('kifi-' + name);
        }
        return list;
      }, []).join(' ');
    },

    render: function () {
      this.$el.addClass(this.renderStatusClasses());
      this.$el.find('.kifi-message-header-options').empty().append(this.renderPlugins('option'));
      this.$el.find('.kifi-message-header-buttons').empty().append(this.renderPlugins('button'));
      this.$el.find('.kifi-message-header-content').empty().append(this.renderPlugins('content'));
    },

    renderPlugins: function (compName) {
      return this.plugins.map(function (plugin) {
        return plugin.render(compName) || '';
      });
    },

    refresh: function () {
      this.plugins.forEach(function (plugin) {
        if (plugin.refresh) {
          plugin.refresh();
        }
      });
    }
  };
})(jQuery, this);
