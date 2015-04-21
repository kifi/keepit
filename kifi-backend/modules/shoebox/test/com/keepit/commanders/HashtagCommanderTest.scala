package com.keepit.commanders

import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class HashtagCommanderTest extends Specification with ShoeboxTestInjector {
  def modules = Nil

  "HashtagCommander" should {

    "find hashtags in string" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[HashtagCommander]
        commander.findAllHashtags("#TLDR") === Seq("TLDR")
        commander.findAllHashtags("#TLDR I love #turtles") === Seq("TLDR", "turtles")
        commander.findAllHashtags("#TLDR I love #turtles#") === Seq("TLDR")
        commander.findAllHashtags("#TLDR I love #turtles\u00a0and\u00a0lobsters") === Seq("TLDR", "turtles\u00a0and\u00a0lobsters")
        commander.findAllHashtags("something something #TLDR") === Seq("TLDR")
        commander.findAllHashtags("#TLDR something something #TLDR") === Seq("TLDR", "TLDR")
        commander.findAllHashtags("# something something #") === Seq()
        commander.findAllHashtags("#TLDR something #asdf something #qwer") === Seq("TLDR", "asdf", "qwer")
        1 === 1
      }
    }
  }
}
