package com.keepit.commanders

import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class HashtagCommanderTest extends Specification with ShoeboxTestInjector {
  def modules = Nil

  "HashtagCommander" should {

    "find hashtags in string" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[HashtagCommander]
        commander.findAllHashtags("") === Set()
        commander.findAllHashtags("#TLDR") === Set("TLDR")
        commander.findAllHashtags("#TLDR I love #turtles") === Set("TLDR", "turtles")
        commander.findAllHashtags("#TLDR I love #turtles#") === Set("TLDR")
        commander.findAllHashtags("#TLDR I love #turtles\u00a0and\u00a0lobsters") === Set("TLDR", "turtles\u00a0and\u00a0lobsters")
        commander.findAllHashtags("something something #TLDR") === Set("TLDR")
        commander.findAllHashtags("#TLDR something something #TLDR") === Set("TLDR")
        commander.findAllHashtags("# something something #") === Set()
        commander.findAllHashtags("#TLDR something #asdf something #qwer") === Set("TLDR", "asdf", "qwer")
      }
    }

    "add set of hashtags to string" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[HashtagCommander]
        commander.appendHashtagsToString("TLDR", Set()) === "TLDR"
        commander.appendHashtagsToString("", Set("TLDR")) === "#TLDR"
        commander.appendHashtagsToString("I love", Set("turtles")) === "I love #turtles"
        commander.appendHashtagsToString("#turtles I love", Set("TLDR", "reptiles")) === "#turtles I love #TLDR #reptiles"
        commander.appendHashtagsToString("TLDR", Set("TLDR")) === "TLDR #TLDR"
        commander.appendHashtagsToString("#TLDR", Set("TLDR")) === "#TLDR #TLDR" // ?
      }
    }

    "remove set of hashtags from string" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[HashtagCommander]
        commander.removeHashtagsFromString("", Set("asdf")) === ""
        commander.removeHashtagsFromString("asdf", Set()) === "asdf"
        commander.removeHashtagsFromString("#TLDR", Set("TLDR")) === ""
        commander.removeHashtagsFromString("I love #turtles", Set("turtles")) === "I love"
        commander.removeHashtagsFromString("I love #turtles", Set("turtles", "lizards")) === "I love"
        commander.removeHashtagsFromString("#turtles I love #lizards", Set("turtles", "lizards")) === "I love"
        commander.removeHashtagsFromString("#turtles I love #lizards", Set("lizards")) === "#turtles I love"
        commander.removeHashtagsFromString("#turtles I love #lizards", Set("turtles")) === "I love #lizards"
        commander.removeHashtagsFromString("#turtles I love #reptiles #lizards", Set("lizards")) === "#turtles I love #reptiles"
        commander.removeHashtagsFromString("#turtles I love #reptiles #lizards", Set("turtles", "reptiles")) === "I love #lizards"
        commander.removeHashtagsFromString("#turtles #reptiles #lizards", Set("lizards", "turtles", "reptiles")) === ""
        commander.removeHashtagsFromString("I love #reptiles#", Set("reptiles")) === "I love #reptiles#"
      }
    }

  }
}
