package com.keepit.test

import com.keepit.inject._
import com.keepit.common.db.slick.{H2, Database}
import com.keepit.model._
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.db.{TestSlickSessionProvider, DbInfo}
import scala.slick.session.{Database => SlickDatabase}
import com.keepit.common.mail.ElectronicMailRepo
import com.google.inject.Injector

trait DbRepos { self: InjectorProvider =>

  def db(implicit injector: Injector) = inject[Database]
  def userSessionRepo(implicit injector: Injector) = inject[UserSessionRepo]
  def userRepo(implicit injector: Injector) = inject[UserRepo]
  def basicUserRepo(implicit injector: Injector) = inject[BasicUserRepo]
  def userConnRepo(implicit injector: Injector) = inject[UserConnectionRepo]
  def socialConnRepo(implicit injector: Injector) = inject[SocialConnectionRepo]
  def uriRepo(implicit injector: Injector) = inject[NormalizedURIRepo]
  def urlRepo(implicit injector: Injector) = inject[URLRepo]
  def bookmarkRepo(implicit injector: Injector) = inject[BookmarkRepo]
  def commentRepo(implicit injector: Injector) = inject[CommentRepo]
  def commentReadRepo(implicit injector: Injector) = inject[CommentReadRepo]
  def commentRecipientRepo(implicit injector: Injector) = inject[CommentRecipientRepo]
  def socialUserInfoRepo(implicit injector: Injector) = inject[SocialUserInfoRepo]
  def installationRepo(implicit injector: Injector) = inject[KifiInstallationRepo]
  def userExperimentRepo(implicit injector: Injector) = inject[UserExperimentRepo]
  def emailAddressRepo(implicit injector: Injector) = inject[EmailAddressRepo]
  def invitationRepo(implicit injector: Injector) = inject[InvitationRepo]
  def unscrapableRepo(implicit injector: Injector) = inject[UnscrapableRepo]
  def notificationRepo(implicit injector: Injector) = inject[UserNotificationRepo]
  def scrapeInfoRepo(implicit injector: Injector) = inject[ScrapeInfoRepo]
  def phraseRepo(implicit injector: Injector) = inject[PhraseRepo]
  def collectionRepo(implicit injector: Injector) = inject[CollectionRepo]
  def keepToCollectionRepo(implicit injector: Injector) = inject[KeepToCollectionRepo]
  def electronicMailRepo(implicit injector: Injector) = inject[ElectronicMailRepo]
  def sessionProvider(implicit injector: Injector) = inject[TestSlickSessionProvider]
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