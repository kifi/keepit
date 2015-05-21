package com.keepit.controllers.admin

import com.keepit.common.akka.SafeFuture
import com.keepit.rover.RoverServiceClient
import play.api.libs.iteratee.{ Concurrent, Enumerator }
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Await }
import scala.concurrent.duration.DurationInt
import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIRepo
import views.html
import com.keepit.model.Restriction
import com.keepit.model.KeepRepo
import scala.collection.mutable.ArrayBuffer

class AdminPornDetectorController @Inject() (
    rover: RoverServiceClient,
    db: Database,
    uriRepo: NormalizedURIRepo,
    bmRepo: KeepRepo,
    val userActionsHelper: UserActionsHelper,
    implicit val executionContext: ExecutionContext) extends AdminUserActions {

  private def tokenize(query: String): Array[String] = {
    query.split("[^a-zA-Z0-9]").filter(!_.isEmpty).map { _.toLowerCase }
  }

  def index() = AdminUserPage { implicit request =>
    Ok(html.admin.pornDetector())
  }

  def detect() = AdminUserPage { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val text = body.get("query").get
    val numBlocks = tokenize(text).sliding(10, 10).size
    val badTexts = Await.result(rover.detectPorn(text), 15 seconds)
    val badInfo = badTexts.map { x => x._1 + " ---> " + x._2 }.mkString("\n")
    val msg = if (badTexts.size == 0) "input text is clean" else s"${badTexts.size} out of ${numBlocks} blocks look suspicious:\n" + badInfo
    Ok(msg.replaceAll("\n", "\n<br>"))
  }

  def pornUrisView(page: Int, publicOnly: Boolean) = AdminUserPage { implicit request =>
    val uris = db.readOnlyReplica { implicit s => uriRepo.getRestrictedURIs(Restriction.ADULT) }.sortBy(-_.updatedAt.getMillis())
    val PAGE_SIZE = 100

    val retUris = publicOnly match {
      case false => uris.toArray
      case true => {
        val need = (page + 1) * PAGE_SIZE
        val buf = new ArrayBuffer[NormalizedURI]()
        var (i, cnt) = (0, 0)
        db.readOnlyReplica { implicit s =>
          while (i < uris.size && cnt < need) {
            val bms = bmRepo.getByUri(uris(i).id.get)
            if (bms.exists(_.isPrivate == false)) {
              buf.append(uris(i))
              cnt += 1
            }
            i += 1
          }
        }
        buf.toArray
      }
    }

    val pageCount = (retUris.size * 1.0 / PAGE_SIZE).ceil.toInt

    Ok(html.admin.pornUris(retUris.drop(page * PAGE_SIZE).take(PAGE_SIZE), retUris.size, page, pageCount, publicOnly))
  }

  def removeRestrictions() = AdminUserPage { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val ids = body.get("uriIds").get.split(",").map { id => Id[NormalizedURI](id.toLong) }
    db.readWrite { implicit s =>
      ids.foreach { id =>
        val uri = uriRepo.get(id)
        if (uri.restriction == Some(Restriction.ADULT)) uriRepo.save(uri.copy(restriction = None))
      }
    }
    Ok(s"${ids.size} uris' adult restriction removed")
  }

  def whitelist() = AdminUserPage { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val whitelist = body.get("whitelist").get
    val cleaned = Await.result(rover.whitelist(whitelist), 5 seconds)
    Ok(s"following words are cleaned: " + cleaned)
  }

  def getPornDetectorModel = AdminUserPage.async { implicit request =>
    val modelFuture = rover.getPornDetectorModel()
    for (model <- modelFuture) yield Ok(Json.toJson(model))
  }

  def massMarkAsAdult() = AdminUserPage { implicit request =>
    val data: Seq[Id[NormalizedURI]] = Seq(829528, 830819, 908531, 1006924, 1103197, 1120184, 1137738, 1162746, 1162970, 1234789, 1353202, 1353241, 1368209, 1368730, 1369835, 1720903, 1725518, 1752231, 1752501, 1834381, 1837862, 1868889, 2151665, 2613991, 2622609, 2770868, 2818734, 3350098, 3350114, 3350153, 3350161, 3389430, 3389440, 3389442, 3389484, 3389593, 3484477, 3484525, 3484556, 3484569, 3484606, 3539058, 3540520, 3542174, 3542325, 3542332, 3542371, 3542406, 3542423, 3542449, 3542453, 3542469, 3610296, 3612167, 3638605, 3638633, 3638658, 3638714, 3716434, 4137171, 4137293, 4137345, 4151207, 4179670, 4182018, 4182741, 4334430, 4348583, 4348643, 4348735, 4369434, 4379373, 4414452, 4424043, 4528659, 4528963, 4529342, 4529619, 4530016, 4530074, 4530075, 4530914, 4531252, 4531698, 4532199, 4532959, 4533502, 4549027, 4550053, 4550269, 4550418, 4563784, 4620305, 4620316, 4620347, 4620352, 4620370, 4620385, 4620437, 4620498, 4620553, 4620589, 4620600, 4620636, 4620647, 4620714, 4620770, 4621128, 4621202, 4674065, 4735558, 4841361, 4887929, 5009320, 5012386, 5052671, 5052781, 5100366, 5100481, 5102202, 5102592, 5103256, 5103269, 5103962, 5104190, 5104454, 5188585, 5188752, 5188872, 5188944).map(x => Id[NormalizedURI](x.toLong))
    val enum: Enumerator[String] = Concurrent.unicast(onStart = (channel: Concurrent.Channel[String]) =>
      SafeFuture("Uri Adultification") {
        data.foreach { id =>
          db.readWrite { implicit session =>
            uriRepo.updateURIRestriction(id, Some(Restriction.ADULT))
          }
          channel.push(id.id.toString + ",")
          Thread.sleep(100)
        }
        channel.eofAndEnd()
      }
    )
    Ok.chunked(enum)
  }
}
