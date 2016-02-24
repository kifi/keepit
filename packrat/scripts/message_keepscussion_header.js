// @require styles/keeper/message_keepscussion_header.css
// @require styles/keeper/keep_box.css
// @require scripts/html/keeper/message_keepscussion_header.js
// @require scripts/html/keeper/keep_box_lib.js
// @require scripts/lib/jquery.js
// @require scripts/render.js

/**
 * --------------------------
 *  Message Keepscussion Header
 * --------------------------
 *
 * Message Keepscussion Header is an UI component that manages
 * the elements of messages related to keeps, including libraries
 * that the keep belongs to, and its privacy.
 */

k.messageKeepscussionHeader = k.messageKeepscussionHeader || (function ($, win) {
  'use strict';

  return {
    /**
     * Parent UI Component. e.g. Message Header
     *
     * @property {Object}
     */
    parent: null,

    initialized: false,

    /**
     * Initializes a Message Participants.
     */
    init: function () {
      this.initialized = true;
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
        case 'content':
          return this.renderContent();
      }
    },

    /**
     * Returns the current state object excluding UI states.
     *
     * @return {Object} A state object
     */
    getView: function () {
      var keep = this.parent.keep;

      return {
        keep: keep,
        librariesPlural: keep && keep.libraries && keep.libraries.length !== 1
      };
    },

    /**
     * Renders and returns html for participants container.
     *
     * @return {string} participants html
     */
    renderContent: function () {
      var keep = this.parent.keep;
      var libraries;
      var $rendered;
      var html = '';

      if (keep && keep.libraries && keep.libraries.length > 0) {
        $rendered = $(k.render('html/keeper/message_keepscussion_header', this.getView(), {  'keep_box_lib': 'keep_box_lib' }));

        // Change the :javascript link to be an actual link to the library.
        libraries = keep.libraries;
        if (libraries && libraries[0]) {
          $rendered
          .find('.kifi-keep-box-lib')
          .attr('href', 'https://www.kifi.com' + libraries[0].path)
          .attr('target','_blank');
        }

        html = $rendered;
      }

      return html;
    },

    /**
     * It removes all event listeners and caches to elements.
     */
    destroy: function () {
      this.initialized = false;
      this.parent = null;
    }
  };
})(jQuery, this);
