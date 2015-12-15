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
      Hashtags.addTagsToString("", Seq.empty) === ""
      Hashtags.addTagsToString("a", Seq.empty) === "a"
      Hashtags.addTagsToString("a", Seq("b")) === "a [#b]"
      Hashtags.addTagsToString("a", Seq("b", "cd")) === "a [#b] [#cd]"

      Hashtags.addTagsToString("", Seq("a", "b", "cd")) === "[#a] [#b] [#cd]"
      Hashtags.addTagsToString("[#a]", Seq("b", "cd")) === "[#a] [#b] [#cd]"

      Hashtags.addTagsToString("a", Seq("\\b")) === "a [#\\\\b]"
      Hashtags.addTagsToString("a", Seq("[b")) === "a [#[b]"
      Hashtags.addTagsToString("a", Seq("b]")) === "a [#b\\]]"
    }

    "add new hashtags to string" in {
      Hashtags.addTagsToString("", Seq.empty) === ""
      Hashtags.addTagsToString("a", Seq.empty) === "a"
      Hashtags.addTagsToString("a", Seq("b")) === "a [#b]"
      Hashtags.addTagsToString("[#b] a", Seq("b")) === "[#b] a"
      Hashtags.addTagsToString("[#b] a", Seq("b", "c")) === "[#b] a [#c]"
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
      Hashtags.removeTagNamesFromString("", Set("asdf")) === ""
      Hashtags.removeTagNamesFromString("asdf", Set("asdf")) === "asdf"
      Hashtags.removeTagNamesFromString("#[asdf]", Set("asdf")) === "#[asdf]"
      Hashtags.removeTagNamesFromString("[\\#asdf]", Set("asdf")) === "[\\#asdf]"
      Hashtags.removeTagNamesFromString("[#asdf]", Set("asdf")) === ""
      Hashtags.removeTagNamesFromString("[#asdf]]", Set("asdf")) === "]"
      Hashtags.removeTagNamesFromString("[#asdf\\]]", Set("asdf")) === "[#asdf\\]]" //'#asdf\]' does not match '#asdf'
      Hashtags.removeTagNamesFromString("[#asd[f\\]]", Set("asdf")) === "[#asd[f\\]]" //'#asd[f\]' does not match '#asdf'
      Hashtags.removeTagNamesFromString("[##asdf]", Set("asdf")) === "[##asdf]"
      Hashtags.removeTagNamesFromString("[[#asdf]]", Set("asdf")) === "[]"

      Hashtags.removeTagNamesFromString("[#asdf] [#qwer]", Set("asdf")) === "[#qwer]"
      Hashtags.removeTagNamesFromString("[#asdf] [#qwer]", Set("asdf", "qwer")) === ""
      Hashtags.removeTagNamesFromString("[#asdf] a [#qwer]", Set("asdf", "qwer")) === "a"
      Hashtags.removeTagNamesFromString("a [#asdf] b [#qwer] c", Set("asdf", "qwer")) === "a  b  c"
    }

    "replace specific hashtags from a string" in {
      Hashtags.replaceTagNameFromString("", "asdf", "dfgh") === ""
      Hashtags.replaceTagNameFromString("asdf", "asdf", "") === "asdf"
      Hashtags.replaceTagNameFromString("#[asdf]", "asdf", "dfgh") === "#[asdf]"

      Hashtags.replaceTagNameFromString("[#asdf]", "Asdf", "dfgh") === "[#dfgh]"
      Hashtags.replaceTagNameFromString("[#Asdf]", "asdf", "dfgh") === "[#dfgh]"
      Hashtags.replaceTagNameFromString("[#asdf]", "asdf", "Asdf") === "[#Asdf]"

      Hashtags.replaceTagNameFromString("[\\#asdf]", "asdf", "dfgh") === "[\\#asdf]"
      Hashtags.replaceTagNameFromString("[#asdf]", "Asdf", "") === ""

      Hashtags.replaceTagNameFromString("[#asdf] [#qwer]", "asdf", "dfgh") === "[#dfgh] [#qwer]"
      Hashtags.replaceTagNameFromString("[#asdf] [#qwer]", "asdf", "qwer") === "[#qwer] [#qwer]"
      Hashtags.replaceTagNameFromString("[#asdf] a [#qwer]", "asdf", "dfgh") === "[#dfgh] a [#qwer]"
      Hashtags.replaceTagNameFromString("a [#asdf] b [#qwer] c", "asdf", "dfgh") === "a [#dfgh] b [#qwer] c"
    }

  }
}
