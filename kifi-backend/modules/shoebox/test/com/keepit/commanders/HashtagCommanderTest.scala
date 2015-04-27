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
        commander.findAllHashtagNames("") === Set.empty
        commander.findAllHashtagNames("asdf") === Set.empty
        commander.findAllHashtagNames("#[asdf]") === Set.empty
        commander.findAllHashtagNames("""[\#asdf]""") === Set.empty
        commander.findAllHashtagNames("""[#123""") === Set.empty
        commander.findAllHashtagNames("""[#]""") === Set.empty

        commander.findAllHashtagNames("""[#123]""") === Set("123")
        commander.findAllHashtagNames("""[#asdf]""") === Set("asdf")
        commander.findAllHashtagNames("""[#asd[f\]]""") === Set("asd[f]")
        commander.findAllHashtagNames("""[#asd[f\]\\]""") === Set("asd[f]\\")

        commander.findAllHashtagNames("""[#asdf] [\#qwer]""") === Set("asdf")
        commander.findAllHashtagNames("""[#asdf] [#qwer]""") === Set("asdf", "qwer")
        commander.findAllHashtagNames("""[#asdf] [##qwer]""") === Set("asdf", "#qwer")
        commander.findAllHashtagNames("""[#a] [#b] [#c] [#\\] [#\]]""") === Set("a", "b", "c", "\\", "]")
      }
    }

    "add list of hashtags to string" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[HashtagCommander]
        commander.appendHashtagNamesToString("", Seq.empty) === ""
        commander.appendHashtagNamesToString("a", Seq.empty) === "a"
        commander.appendHashtagNamesToString("a", Seq("b")) === "a [#b]"
        commander.appendHashtagNamesToString("a", Seq("b", "cd")) === "a [#b] [#cd]"

        commander.appendHashtagNamesToString("", Seq("a", "b", "cd")) === "[#a] [#b] [#cd]"
        commander.appendHashtagNamesToString("[#a]", Seq("b", "cd")) === "[#a] [#b] [#cd]"

        commander.appendHashtagNamesToString("a", Seq("\\b")) === "a [#\\\\b]"
        commander.appendHashtagNamesToString("a", Seq("[b")) === "a [#[b]"
        commander.appendHashtagNamesToString("a", Seq("b]")) === "a [#b\\]]"
      }
    }

    "remove all hashtags from a string" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[HashtagCommander]
        commander.removeAllHashtagsFromString("") === ""
        commander.removeAllHashtagsFromString("asdf") === "asdf"
        commander.removeAllHashtagsFromString("#[asdf]") === "#[asdf]"
        commander.removeAllHashtagsFromString("[\\#asdf]") === "[\\#asdf]"
        commander.removeAllHashtagsFromString("[#asdf]") === ""
        commander.removeAllHashtagsFromString("[#asdf]]") === "]"
        commander.removeAllHashtagsFromString("[#asdf\\]]") === ""
        commander.removeAllHashtagsFromString("[#asd[f\\]]") === ""
        commander.removeAllHashtagsFromString("[##asdf]") === ""
        commander.removeAllHashtagsFromString("[[#asdf]]") === "[]"

        commander.removeAllHashtagsFromString("[#asdf] [#qwer]") === ""
        commander.removeAllHashtagsFromString("[#asdf] a [#qwer]") === "a"
        commander.removeAllHashtagsFromString("a [#asdf] b [#qwer] c") === "a  b  c"
      }
    }

    "remove specific hashtags from a string" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[HashtagCommander]
        commander.removeHashtagNamesFromString("", Set("asdf")) === ""
        commander.removeHashtagNamesFromString("asdf", Set("asdf")) === "asdf"
        commander.removeHashtagNamesFromString("#[asdf]", Set("asdf")) === "#[asdf]"
        commander.removeHashtagNamesFromString("[\\#asdf]", Set("asdf")) === "[\\#asdf]"
        commander.removeHashtagNamesFromString("[#asdf]", Set("asdf")) === ""
        commander.removeHashtagNamesFromString("[#asdf]]", Set("asdf")) === "]"
        commander.removeHashtagNamesFromString("[#asdf\\]]", Set("asdf")) === "[#asdf\\]]" //'#asdf\]' does not match '#asdf'
        commander.removeHashtagNamesFromString("[#asd[f\\]]", Set("asdf")) === "[#asd[f\\]]" //'#asd[f\]' does not match '#asdf'
        commander.removeHashtagNamesFromString("[##asdf]", Set("asdf")) === "[##asdf]"
        commander.removeHashtagNamesFromString("[[#asdf]]", Set("asdf")) === "[]"

        commander.removeHashtagNamesFromString("[#asdf] [#qwer]", Set("asdf")) === "[#qwer]"
        commander.removeHashtagNamesFromString("[#asdf] [#qwer]", Set("asdf", "qwer")) === ""
        commander.removeHashtagNamesFromString("[#asdf] a [#qwer]", Set("asdf", "qwer")) === "a"
        commander.removeHashtagNamesFromString("a [#asdf] b [#qwer] c", Set("asdf", "qwer")) === "a  b  c"
      }
    }

  }
}
