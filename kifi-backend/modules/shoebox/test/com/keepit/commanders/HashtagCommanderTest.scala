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
        commander.findAllHashtags("#TLDR") === Set()
        commander.findAllHashtags("\\[#TLDR\\]") === Set()
        commander.findAllHashtags("[#TLDR]") === Set("TLDR")
        commander.findAllHashtags("[#TLDR] I love [#turtles]") === Set("TLDR", "turtles")
        commander.findAllHashtags("[#TLDR] I love [#turtles and lobsters]") === Set("TLDR", "turtles and lobsters")
        commander.findAllHashtags("[#TLDR] something something [#TLDR]") === Set("TLDR")
        commander.findAllHashtags("[#] something something [#]") === Set()
        commander.findAllHashtags("[#TLDR] something [#asdf] something [#qwer]") === Set("TLDR", "asdf", "qwer")
        commander.findAllHashtags("[#a] [#b] [#c]") === Set("a", "b", "c")
      }
    }

    "add set of hashtags to string" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[HashtagCommander]
        commander.appendHashtagsToString("TLDR", Set()) === "TLDR"
        commander.appendHashtagsToString("", Set("TLDR")) === "[#TLDR]"
        commander.appendHashtagsToString("I love", Set("turtles")) === "I love [#turtles]"
        commander.appendHashtagsToString("#turtles I love", Set("TLDR", "reptiles")) === "#turtles I love [#TLDR] [#reptiles]"
        commander.appendHashtagsToString("TLDR", Set("TLDR")) === "TLDR [#TLDR]"
        commander.appendHashtagsToString("[#TLDR]", Set("TLDR")) === "[#TLDR] [#TLDR]" // ?
      }
    }

    "remove set of hashtags from string" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[HashtagCommander]
        commander.removeHashtagsFromString("", Set("asdf")) === ""
        commander.removeHashtagsFromString("asdf", Set()) === "asdf"
        commander.removeHashtagsFromString("[#TLDR]", Set("TLDR")) === ""
        commander.removeHashtagsFromString("I like [#turtles]", Set("turtles")) === "I like"
        commander.removeHashtagsFromString("I like [#turtles]", Set("turtles", "lizards")) === "I like"
        commander.removeHashtagsFromString("[#turtles] I like [#lizards]", Set("turtles", "lizards")) === "I like"
        commander.removeHashtagsFromString("[#turtles] I like [#lizards]", Set("lizards")) === "[#turtles] I like"
        commander.removeHashtagsFromString("[#turtles] I like [#lizards]", Set("turtles")) === "I like [#lizards]"
        commander.removeHashtagsFromString("[#turtles] I like [#reptiles] [#lizards]", Set("lizards")) === "[#turtles] I like [#reptiles]"
        commander.removeHashtagsFromString("[#turtles] I like [#reptiles] [#lizards]", Set("turtles", "reptiles")) === "I like [#lizards]"
        commander.removeHashtagsFromString("[#a] [#b] [#c]", Set("a", "b", "c")) === ""
        commander.removeHashtagsFromString("[#a] [#a] [#a]", Set("a", "b", "c")) === ""
      }
    }

  }
}
