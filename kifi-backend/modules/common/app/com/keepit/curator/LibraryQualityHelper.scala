package com.keepit.curator

import com.google.inject.{ Singleton, Inject }

@Singleton
class LibraryQualityHelper @Inject() () {

  val LowQualityLibraryNamesRe = "(?i)(test|delicious|bookmark|pocket|kippt|asdf|pinboard|import|instapaper)".r

  def isBadLibraryName(name: String): Boolean = {
    name.size <= 2 || LowQualityLibraryNamesRe.findFirstIn(name).isDefined // name looks bad...
  }
}
