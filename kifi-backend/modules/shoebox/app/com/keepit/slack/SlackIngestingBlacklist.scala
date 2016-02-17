package com.keepit.slack

object SlackIngestingBlacklist {

  def parseBlacklist(blacklistStr: String): Seq[String] = {
    blacklistStr.split(',')
      .filter(_.length >= 4) // too short
      .filter(_.count(_ == '*') <= 3) // too many wildcards
      .filter(_.length < 50) // too long
      .map(_.trim)
  }

  def blacklistedUrl(url: String, blacklistedPaths: Seq[String]): Boolean = {
    val noProto = proto.findFirstMatchIn(url).map(_.after.toString).getOrElse(url)
    if (noProto.startsWith("www.")) {
      val noWWW = noProto.substring(4)
      blacklistedPaths.exists(matches(noProto, _)) || blacklistedPaths.exists(matches(noWWW, _))
    } else {
      blacklistedPaths.exists(matches(noProto, _))
    }
  }

  private val proto = "^\\w+://".r

  // Glob-style prefix string matcher
  // Globs expressions supported are literals and * (greedy wildcard)
  // There is an implicit * after the end of glob.
  // Glob *.github.com/kifi matches gist.github.com/kifi/4242424242
  private def matches(text: String, glob: String): Boolean = {
    val (literal, postWildcard) = {
      val pos = glob.indexOf('*')
      if (pos != -1) {
        (glob.substring(0, pos), Some(glob.substring(pos + 1)))
      } else {
        (glob, None)
      }
    }

    if (literal.length > text.length) {
      false
    } else {
      val prefixMatches = literal.zipWithIndex.foldLeft(true) {
        case (p, (c, i)) =>
          p && literal.substring(i, i + 1).equalsIgnoreCase(text.substring(i, i + 1))
      }
      val restMatches = postWildcard match {
        case None => true // This is the implied * at the end (if there isn't a remaining *, then the rest always matches)
        case Some(rest) =>
          text.zipWithIndex.exists { case (_, i) => matches(text.substring(i), rest) }
      }
      prefixMatches && restMatches
    }
  }
}
