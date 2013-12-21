package com.keepit.shoebox

import com.google.inject.{Provides, Singleton}
import com.keepit.common.db.slick._
import com.keepit.commanders.KeepsAbuseController
import com.keepit.model.BookmarkRepo
import net.codingwell.scalaguice.ScalaModule

case class AbuseControlModule() extends ScalaModule {

  def configure(): Unit = {
  }

  @Provides
  @Singleton
  def keepsAbuseController(bookmarkRepo: BookmarkRepo, db: Database): KeepsAbuseController =
    new KeepsAbuseController(
      absoluteAlert = 5000,
      absoluteError = 20000,
      bookmarkRepo = bookmarkRepo,
      db = db)

}


