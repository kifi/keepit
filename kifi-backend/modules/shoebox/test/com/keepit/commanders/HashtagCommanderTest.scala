package com.keepit.commanders

import com.keepit.model.Hashtag
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class HashtagCommanderTest extends Specification with ShoeboxTestInjector {
  def modules = Nil

  "HashtagCommander" should {

    "find hashtags in string" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[HashtagCommander]
        commander.findAllHashtagNames("") === Set()
        commander.findAllHashtagNames("#TLDR") === Set()
        commander.findAllHashtagNames("\\[#TLDR\\]") === Set()
        commander.findAllHashtagNames("[#TLDR]") === Set("TLDR")
        commander.findAllHashtagNames("[#TLDR] I love [#turtles]") === Set("TLDR", "turtles")
        commander.findAllHashtagNames("[#TLDR] I love [#turtles and lobsters]") === Set("TLDR", "turtles and lobsters")
        commander.findAllHashtagNames("[#TLDR] something something [#TLDR]") === Set("TLDR")
        commander.findAllHashtagNames("[#] something something [#]") === Set()
        commander.findAllHashtagNames("[#TLDR] something [#asdf] something [#qwer]") === Set("TLDR", "asdf", "qwer")
        commander.findAllHashtagNames("[#a] [#b] [#c]") === Set("a", "b", "c")
      }
    }

    "add set of hashtags to string" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[HashtagCommander]

        commander.appendHashtagNamesToString("TLDR", Set()) === "TLDR"
        commander.appendHashtagNamesToString("", Set("TLDR")) === "[#TLDR]"
        commander.appendHashtagNamesToString("I love", Set("turtles")) === "I love [#turtles]"
        commander.appendHashtagNamesToString("#turtles I love", Set("TLDR", "reptiles")) === "#turtles I love [#TLDR] [#reptiles]"
        commander.appendHashtagNamesToString("TLDR", Set("TLDR")) === "TLDR [#TLDR]"
        commander.appendHashtagNamesToString("[#TLDR]", Set("TLDR")) === "[#TLDR] [#TLDR]" // ?
      }
    }

    "remove set of hashtags from string" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[HashtagCommander]
        commander.removeHashtagNamesFromString("", Set("asdf")) === ""
        commander.removeHashtagNamesFromString("asdf", Set()) === "asdf"
        commander.removeHashtagNamesFromString("[#TLDR]", Set("TLDR")) === ""
        commander.removeHashtagNamesFromString("I like [#turtles]", Set("turtles")) === "I like"
        commander.removeHashtagNamesFromString("I like [#turtles]", Set("turtles", "lizards")) === "I like"
        commander.removeHashtagNamesFromString("[#turtles] I like [#lizards]", Set("turtles", "lizards")) === "I like"
        commander.removeHashtagNamesFromString("[#turtles] I like [#lizards]", Set("lizards")) === "[#turtles] I like"
        commander.removeHashtagNamesFromString("[#turtles] I like [#lizards]", Set("turtles")) === "I like [#lizards]"
        commander.removeHashtagNamesFromString("[#turtles] I like [#reptiles] [#lizards]", Set("lizards")) === "[#turtles] I like [#reptiles]"
        commander.removeHashtagNamesFromString("[#turtles] I like [#reptiles] [#lizards]", Set("turtles", "reptiles")) === "I like [#lizards]"
        commander.removeHashtagNamesFromString("[#a] [#b] [#c]", Set("a", "b", "c")) === ""
        commander.removeHashtagNamesFromString("[#a] [#a] [#a]", Set("a", "b", "c")) === ""
      }
    }

  }
}
