package com.keepit.commanders

import org.specs2.mutable.Specification

class HashtagsTest extends Specification {

  "Hashtags" should {
    "find hashtags in string" in {
      Hashtags.findAllHashtagNames("") === Set.empty
      Hashtags.findAllHashtagNames("asdf") === Set.empty
      Hashtags.findAllHashtagNames("#[asdf]") === Set.empty
      Hashtags.findAllHashtagNames("""[\#asdf]""") === Set.empty
      Hashtags.findAllHashtagNames("""[#123""") === Set.empty
      Hashtags.findAllHashtagNames("""[#]""") === Set.empty

      Hashtags.findAllHashtagNames("""[#123]""") === Set("123")
      Hashtags.findAllHashtagNames("""[#asdf]""") === Set("asdf")
      Hashtags.findAllHashtagNames("""[#asd[f\]]""") === Set("asd[f]")
      Hashtags.findAllHashtagNames("""[#asd[f\]\\]""") === Set("asd[f]\\")

      Hashtags.findAllHashtagNames("""[#asdf] [\#qwer]""") === Set("asdf")
      Hashtags.findAllHashtagNames("""[#asdf] [#qwer]""") === Set("asdf", "qwer")
      Hashtags.findAllHashtagNames("""[#asdf] [##qwer]""") === Set("asdf", "#qwer")
      Hashtags.findAllHashtagNames("""[#a] [#b] [#c] [#\\] [#\]]""") === Set("a", "b", "c", "\\", "]")
    }

    "add list of hashtags to string" in {
      Hashtags.appendHashtagNamesToString("", Seq.empty) === ""
      Hashtags.appendHashtagNamesToString("a", Seq.empty) === "a"
      Hashtags.appendHashtagNamesToString("a", Seq("b")) === "a [#b]"
      Hashtags.appendHashtagNamesToString("a", Seq("b", "cd")) === "a [#b] [#cd]"

      Hashtags.appendHashtagNamesToString("", Seq("a", "b", "cd")) === "[#a] [#b] [#cd]"
      Hashtags.appendHashtagNamesToString("[#a]", Seq("b", "cd")) === "[#a] [#b] [#cd]"

      Hashtags.appendHashtagNamesToString("a", Seq("\\b")) === "a [#\\\\b]"
      Hashtags.appendHashtagNamesToString("a", Seq("[b")) === "a [#[b]"
      Hashtags.appendHashtagNamesToString("a", Seq("b]")) === "a [#b\\]]"
    }

    "add new hashtags to string" in {
      Hashtags.addNewHashtagNamesToString("", Seq.empty) === ""
      Hashtags.addNewHashtagNamesToString("a", Seq.empty) === "a"
      Hashtags.addNewHashtagNamesToString("a", Seq("b")) === "a [#b]"
      Hashtags.addNewHashtagNamesToString("[#b] a", Seq("b")) === "[#b] a"
      Hashtags.addNewHashtagNamesToString("[#b] a", Seq("b", "c")) === "[#b] a [#c]"
    }

    "remove all hashtags from a string" in {
      Hashtags.removeAllHashtagsFromString("") === ""
      Hashtags.removeAllHashtagsFromString("asdf") === "asdf"
      Hashtags.removeAllHashtagsFromString("#[asdf]") === "#[asdf]"
      Hashtags.removeAllHashtagsFromString("[\\#asdf]") === "[\\#asdf]"
      Hashtags.removeAllHashtagsFromString("[#asdf]") === ""
      Hashtags.removeAllHashtagsFromString("[#asdf]]") === "]"
      Hashtags.removeAllHashtagsFromString("[#asdf\\]]") === ""
      Hashtags.removeAllHashtagsFromString("[#asd[f\\]]") === ""
      Hashtags.removeAllHashtagsFromString("[##asdf]") === ""
      Hashtags.removeAllHashtagsFromString("[[#asdf]]") === "[]"

      Hashtags.removeAllHashtagsFromString("[#asdf] [#qwer]") === ""
      Hashtags.removeAllHashtagsFromString("[#asdf] a [#qwer]") === "a"
      Hashtags.removeAllHashtagsFromString("a [#asdf] b [#qwer] c") === "a  b  c"
    }

    "remove specific hashtags from a string" in {
      Hashtags.removeHashtagNamesFromString("", Set("asdf")) === ""
      Hashtags.removeHashtagNamesFromString("asdf", Set("asdf")) === "asdf"
      Hashtags.removeHashtagNamesFromString("#[asdf]", Set("asdf")) === "#[asdf]"
      Hashtags.removeHashtagNamesFromString("[\\#asdf]", Set("asdf")) === "[\\#asdf]"
      Hashtags.removeHashtagNamesFromString("[#asdf]", Set("asdf")) === ""
      Hashtags.removeHashtagNamesFromString("[#asdf]]", Set("asdf")) === "]"
      Hashtags.removeHashtagNamesFromString("[#asdf\\]]", Set("asdf")) === "[#asdf\\]]" //'#asdf\]' does not match '#asdf'
      Hashtags.removeHashtagNamesFromString("[#asd[f\\]]", Set("asdf")) === "[#asd[f\\]]" //'#asd[f\]' does not match '#asdf'
      Hashtags.removeHashtagNamesFromString("[##asdf]", Set("asdf")) === "[##asdf]"
      Hashtags.removeHashtagNamesFromString("[[#asdf]]", Set("asdf")) === "[]"

      Hashtags.removeHashtagNamesFromString("[#asdf] [#qwer]", Set("asdf")) === "[#qwer]"
      Hashtags.removeHashtagNamesFromString("[#asdf] [#qwer]", Set("asdf", "qwer")) === ""
      Hashtags.removeHashtagNamesFromString("[#asdf] a [#qwer]", Set("asdf", "qwer")) === "a"
      Hashtags.removeHashtagNamesFromString("a [#asdf] b [#qwer] c", Set("asdf", "qwer")) === "a  b  c"
    }
  }
}
