package com.keepit.commanders

import java.util.concurrent.atomic.AtomicInteger

import com.keepit.commander.{ HelpRankEventTrackingCommander }
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time._
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.model.helprank.KeepDiscoveryRepo
import com.keepit.shoebox.{ ShoeboxServiceClient, FakeShoeboxServiceClientImpl, FakeShoeboxServiceModule }
import com.keepit.test._
import net.codingwell.scalaguice.ScalaModule
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions

import scala.concurrent.Await
import scala.concurrent.duration._

class HelpRankEventTrackingCommanderTest extends Specification with HeimdalTestInjector with NoTimeConversions {

  val keepIdCounter = new AtomicInteger(0)
  def mkKeep(userId: Id[User], ts: DateTime = currentDateTime, idOpt: Option[Id[Keep]] = None, libraryId: Option[Id[Library]] = None)(uri: NormalizedURI): Keep = {
    val id = idOpt getOrElse Id[Keep](keepIdCounter.incrementAndGet)
    Keep(id = Some(id), createdAt = ts, updatedAt = ts, uriId = uri.id.get, url = uri.url, urlId = Id[URL](uri.id.get.id), visibility = Keep.isPrivateToVisibility(false), userId = userId, source = KeepSource.keeper, libraryId = libraryId, inDisjointLib = true)
  }

  val uriIdCounter = new AtomicInteger(0)
  val uriSeqCounter = new AtomicInteger(0)
  def mkURI(url: String, idOpt: Option[Id[NormalizedURI]] = None, seqOpt: Option[SequenceNumber[NormalizedURI]] = None, tsOpt: Option[DateTime] = None): NormalizedURI = {
    val id = idOpt getOrElse Id[NormalizedURI](uriIdCounter.incrementAndGet)
    val seq = seqOpt getOrElse SequenceNumber[NormalizedURI](uriSeqCounter.incrementAndGet)
    val ts = tsOpt getOrElse currentDateTime
    NormalizedURI(id = Some(id), createdAt = ts, updatedAt = ts, url = url, urlHash = UrlHash.hashUrl(url), seq = seq)
  }

  val FORTYTWO = "42"
  val KIFI = "KIFI"
  val GOOG = "GOOG"
  val BING = "BING"
  val LNKD = "LNKD"
  val APPL = "APPL"

  def modules: Seq[ScalaModule] = Seq(
    FakeHttpClientModule(),
    FakeShoeboxServiceModule()
  )

  "EventTrackingCommander" should {
    "track reco click event" in {
      withDb(modules: _*) { implicit injector =>
        val ts = currentDateTime
        val u1 = User(id = Some(Id[User](1)), createdAt = ts, updatedAt = ts, firstName = "Key", lastName = "Fei", username = Username("test"), normalizedUsername = "test")
        val u2 = User(id = Some(Id[User](2)), createdAt = ts, updatedAt = ts, firstName = "Foo", lastName = "Bar", username = Username("test"), normalizedUsername = "test")
        val u3 = User(id = Some(Id[User](3)), createdAt = ts, updatedAt = ts, firstName = "Ping", lastName = "Pong", username = Username("test"), normalizedUsername = "test")

        val uri42 = mkURI("http://42go.com")
        val uriKifi = mkURI("http://kifi.com")
        val uriGoog = mkURI("http://google.com")
        val uriBing = mkURI("http://bing.com")
        val uriLnkd = mkURI("http://linkedin.com")
        val uriAppl = mkURI("http://apple.com")
        val uris = Map(
          FORTYTWO -> uri42,
          KIFI -> uriKifi,
          GOOG -> uriGoog,
          BING -> uriBing,
          LNKD -> uriLnkd,
          APPL -> uriAppl
        )

        val mkKeep1 = mkKeep(u1.id.get, currentDateTime) _
        val k1 = Map(
          FORTYTWO -> mkKeep1(uri42),
          KIFI -> mkKeep1(uriKifi)
        )
        val keeps1 = k1.values.toSeq

        val mkKeep2 = mkKeep(u2.id.get, currentDateTime) _
        val k2 = Map(
          KIFI -> mkKeep2(uriKifi),
          GOOG -> mkKeep2(uriGoog),
          BING -> mkKeep2(uriBing)
        )
        val keeps2 = k2.values.toSeq

        val mkKeep3 = mkKeep(u3.id.get, currentDateTime) _
        val k3 = Map(
          KIFI -> mkKeep2(uriKifi),
          LNKD -> mkKeep2(uriLnkd),
          BING -> mkKeep2(uriBing)
        )
        val keeps3 = k3.values.toSeq

        val keeps = Map(u1 -> keeps1, u2 -> keeps2, u3 -> keeps3)

        val commander = inject[HelpRankEventTrackingCommander]

        val shoebox = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val savedUsers = shoebox.saveUsers(u1, u2, u3).toVector
        val savedUris = shoebox.saveURIs(uris.values.toSeq: _*).toVector
        val savedKeeps = shoebox.saveBookmarks((keeps1 ++ keeps2): _*).toVector

        val kv = Map(
          "userId" -> ContextDoubleData(u1.id.get.id),
          "uriId" -> ContextDoubleData(uriBing.id.get.id),
          "keepers" -> ContextList(Seq(ContextDoubleData(u2.id.get.id)))
        )
        val event = UserEvent(u1.id.get, new HeimdalContext(kv), UserEventTypes.RECOMMENDATION_USER_ACTION)
        val resF = commander.userClickedFeedItem(event)
        Await.result(resF, 5 seconds)
        db.readWrite { implicit session =>
          val kifiHitCache = inject[SearchHitReportCache]
          val cached = kifiHitCache.get(SearchHitReportKey(u1.id.get, uriBing.id.get))
          cached.exists(k => k.keepers.length == 1) === true
          val kdRepo = inject[KeepDiscoveryRepo]
          val kd2 = kdRepo.getDiscoveriesByKeeper(u2.id.get)
          kd2.isEmpty === false
          kd2.length === 1
          kd2.head.uriId === uriBing.id.get
        }
      }
    }
  }
}
