package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.commanders.gen.KeepActivityGen
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.{ FakeAirbrakeNotifier, AirbrakeNotifier }
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
import com.keepit.common.util.DescriptionElements
import com.keepit.eliza.{ FakeElizaServiceClientImpl, ElizaServiceClient }
import com.keepit.model.{ KeepEventRepo, KeepRecipientsDiff, KeepEventData, Library, User, BasicLibrary, LibraryFactory, KeepToUserRepo, UserFactory, KeepFactory }
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.social.{ BasicAuthor, BasicUser }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.{ Json, JsObject }

class KeepActivityGenTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(FakeClockModule())

  implicit def airbrake(implicit injector: Injector) = inject[AirbrakeNotifier].asInstanceOf[FakeAirbrakeNotifier]
  implicit def imageConfig(implicit injector: Injector) = inject[S3ImageConfig]
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]

  def eliza(implicit injector: Injector) = inject[ElizaServiceClient].asInstanceOf[FakeElizaServiceClientImpl]

  "KeepActivityGen" should {
    "generate keep activity" in {
      withDb(modules: _*) { implicit injector =>
        val keepEventCommander = inject[KeepEventCommander]

        val (user, keep, usersToAdd, libsToAdd) = db.readWrite { implicit s =>
          val user = UserFactory.user().withName("Benjamin", "Button").saved
          val usersToAdd = UserFactory.users(4).saved
          val libsToAdd = LibraryFactory.libraries(8).saved
          val keep = KeepFactory.keep().withUser(user.id.get).saved
          (user, keep, usersToAdd, libsToAdd)
        }
        val basicUser = BasicUser.fromUser(user)
        val ktus = db.readOnlyMaster(implicit s => inject[KeepToUserRepo].getAllByKeepId(keep.id.get))

        val userIdsToAdd = usersToAdd.map(_.id.get).toSet
        val libIdsToAdd = libsToAdd.map(_.id.get).toSet

        val basicUserById = usersToAdd.map(usr => usr.id.get -> BasicUser.fromUser(usr)).toMap + (user.id.get -> basicUser)
        val basicLibById = libsToAdd.map(lib => lib.id.get -> BasicLibrary(lib, basicUser, None)).toMap

        import com.keepit.common.util.DescriptionElements._
        val eventHeaders = db.readWrite { implicit s =>
          Map(userIdsToAdd.take(2) -> true, libIdsToAdd.take(4) -> false, userIdsToAdd.takeRight(2) -> true, libIdsToAdd.takeRight(4) -> false).map {
            case (ids, true) => // users
              val adding = ids.map(id => Id[User](id.id))
              keepEventCommander.registerKeepEvent(keep.id.get, KeepEventData.ModifyRecipients(user.id.get, KeepRecipientsDiff.addUsers(adding)), eventTime = None, source = None)
              val entities = adding.map(id => fromBasicUser(basicUserById(id))).toSeq
              DescriptionElements.formatPlain(DescriptionElements(basicUser, "added", unwordsPretty(entities), "to this discussion"))
            case (ids, false) => // libraries
              val adding = ids.map(id => Id[Library](id.id))
              keepEventCommander.registerKeepEvent(keep.id.get, KeepEventData.ModifyRecipients(user.id.get, KeepRecipientsDiff.addLibraries(adding)), eventTime = None, source = None)
              val entities = adding.map(id => fromBasicLibrary(basicLibById(id))).toSeq
              DescriptionElements.formatPlain(DescriptionElements(basicUser, "added", unwordsPretty(entities), "to this discussion"))
          }.toSeq
        }

        val events = db.readOnlyMaster { implicit s => inject[KeepEventRepo].pageForKeep(keep.id.get, fromTime = None, limit = 10) }

        val activity = KeepActivityGen.generateKeepActivity(
          keep, sourceAttrOpt = None, events = events, discussionOpt = None, ktls = Seq.empty, ktus,
          basicUserById, basicLibById, orgByLibraryId = Map.empty,
          maxEvents = 5)

        activity.events.size === 5
        activity.events.map { event => DescriptionElements.formatPlain(event.header) } === (eventHeaders.reverse :+ "Benjamin Button kept this")

        val jsActivity = Json.toJson(activity)

        val jsTimestamp = ((jsActivity \ "events").as[Seq[JsObject]].head \ "timestamp").asOpt[Long]
        jsTimestamp must beSome(activity.events.head.timestamp.getMillis)

        val initialKeepEvent = activity.events.last
        initialKeepEvent.author === BasicAuthor.fromUser(basicUser)
        1 === 1
      }
    }
  }
}
