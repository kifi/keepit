package com.keepit.test

import com.keepit.inject._
import com.keepit.common.db.slick.{H2, Database}
import com.keepit.model._
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.db.DbInfo
import scala.slick.session.{Database => SlickDatabase}

trait DbRepos {

  import play.api.Play.current

  def db = inject[Database]
  def userSessionRepo = inject[UserSessionRepo]
  def userRepo = inject[UserRepo]
  def basicUserRepo = inject[BasicUserRepo]
  def userConnRepo = inject[UserConnectionRepo]
  def socialConnRepo = inject[SocialConnectionRepo]
  def uriRepo = inject[NormalizedURIRepo]
  def urlRepo = inject[URLRepo]
  def bookmarkRepo = inject[BookmarkRepo]
  def commentRepo = inject[CommentRepo]
  def commentReadRepo = inject[CommentReadRepo]
  def commentRecipientRepo = inject[CommentRecipientRepo]
  def socialUserInfoRepo = inject[SocialUserInfoRepo]
  def installationRepo = inject[KifiInstallationRepo]
  def userExperimentRepo = inject[UserExperimentRepo]
  def emailAddressRepo = inject[EmailAddressRepo]
  def invitationRepo = inject[InvitationRepo]
  def unscrapableRepo = inject[UnscrapableRepo]
  def notificationRepo = inject[UserNotificationRepo]
  def scrapeInfoRepo = inject[ScrapeInfoRepo]
  def phraseRepo = inject[PhraseRepo]
  def collectionRepo = inject[CollectionRepo]
  def keepToCollectionRepo = inject[KeepToCollectionRepo]
}

object TestDbInfo {
  val url = "jdbc:h2:mem:shoebox;USER=shoebox;MODE=MYSQL;MVCC=TRUE;DB_CLOSE_DELAY=-1"
  val dbInfo = new DbInfo() {
    //later on we can customize it by the application name
    lazy val database = SlickDatabase.forURL(url = url)
    lazy val driverName = H2.driverName
    //    lazy val database = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
    //    lazy val driverName = Play.current.configuration.getString("db.shoebox.driver").get
  }
}