package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule, FakeKeepImportsModule }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class KeepDecoratorTest extends Specification with ShoeboxTestInjector {

  def modules = FakeKeepImportsModule() ::
    FakeExecutionContextModule() ::
    FakeShoeboxStoreModule() ::
    FakeSearchServiceClientModule() ::
    FakeCortexServiceClientModule() ::
    FakeScrapeSchedulerModule() ::
    FakeShoeboxServiceModule() ::
    FakeUserActionsModule() ::
    FakeCuratorServiceClientModule() ::
    FakeABookServiceClientModule() ::
    FakeSocialGraphModule() ::
    Nil

  "Keep Decorator" should {

    "escape Markup" in {
      withDb(modules: _*) { implicit injector =>
        val decorator = inject[KeepDecorator]
        decorator.escapeMarkupNotes("") === ""
        decorator.escapeMarkupNotes("asdf") === "asdf"
        decorator.escapeMarkupNotes("@[asdf]") === "@[asdf]"
        decorator.escapeMarkupNotes("#[asdf]") === "#[asdf]"
        decorator.escapeMarkupNotes("@asdf]") === "@asdf]"
        decorator.escapeMarkupNotes("#asdf]") === "#asdf]"

        decorator.escapeMarkupNotes("[\\@asdf]") === "[\\@asdf]"
        decorator.escapeMarkupNotes("[\\#asdf]") === "[\\#asdf]"
        decorator.escapeMarkupNotes("[@asdf]") === "[\\@asdf]"
        decorator.escapeMarkupNotes("[#asdf]") === "[\\#asdf]"
      }
    }

    "unescape Markup" in {
      withDb(modules: _*) { implicit injector =>
        val decorator = inject[KeepDecorator]
        decorator.unescapeMarkupNotes("") === ""
        decorator.unescapeMarkupNotes("asdf") === "asdf"
        decorator.unescapeMarkupNotes("@[asdf]") === "@[asdf]"
        decorator.unescapeMarkupNotes("#[asdf]") === "#[asdf]"
        decorator.unescapeMarkupNotes("@asdf]") === "@asdf]"
        decorator.unescapeMarkupNotes("#asdf]") === "#asdf]"

        decorator.unescapeMarkupNotes("[@asdf]") === "[@asdf]"
        decorator.unescapeMarkupNotes("[#asdf]") === "[#asdf]"
        decorator.unescapeMarkupNotes("[\\@asdf]") === "[@asdf]"
        decorator.unescapeMarkupNotes("[\\#asdf]") === "[#asdf]"

      }
    }
  }
}
