package com.keepit.commanders

import org.specs2.mutable.Specification

class KeepDecoratorTest extends Specification {

  "Keep Decorator" should {

    "escape Markup" in {
      KeepDecorator.escapeMarkupNotes("") === ""
      KeepDecorator.escapeMarkupNotes("asdf") === "asdf"
      KeepDecorator.escapeMarkupNotes("@[asdf]") === "@[asdf]"
      KeepDecorator.escapeMarkupNotes("#[asdf]") === "#[asdf]"
      KeepDecorator.escapeMarkupNotes("@asdf]") === "@asdf]"
      KeepDecorator.escapeMarkupNotes("#asdf]") === "#asdf]"

      KeepDecorator.escapeMarkupNotes("[\\@asdf]") === "[\\@asdf]"
      KeepDecorator.escapeMarkupNotes("[\\#asdf]") === "[\\#asdf]"
      KeepDecorator.escapeMarkupNotes("[@asdf]") === "[\\@asdf]"
      KeepDecorator.escapeMarkupNotes("[#asdf]") === "[\\#asdf]"
    }

    "unescape Markup" in {
      KeepDecorator.unescapeMarkupNotes("") === ""
      KeepDecorator.unescapeMarkupNotes("asdf") === "asdf"
      KeepDecorator.unescapeMarkupNotes("@[asdf]") === "@[asdf]"
      KeepDecorator.unescapeMarkupNotes("#[asdf]") === "#[asdf]"
      KeepDecorator.unescapeMarkupNotes("@asdf]") === "@asdf]"
      KeepDecorator.unescapeMarkupNotes("#asdf]") === "#asdf]"

      KeepDecorator.unescapeMarkupNotes("[@asdf]") === "[@asdf]"
      KeepDecorator.unescapeMarkupNotes("[#asdf]") === "[#asdf]"
      KeepDecorator.unescapeMarkupNotes("[\\@asdf]") === "[@asdf]"
      KeepDecorator.unescapeMarkupNotes("[\\#asdf]") === "[#asdf]"
    }
  }
}
