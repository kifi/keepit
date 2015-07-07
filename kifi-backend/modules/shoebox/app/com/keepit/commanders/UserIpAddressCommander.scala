package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.UserRequest
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient, UserAgent }
import com.keepit.common.service.IpAddress
import com.keepit.common.time._
import com.keepit.model._
import org.joda.time.{ DateTime, Period }
import play.api.libs.json.{ JsValue, JsObject, Json }

import scala.concurrent.{ ExecutionContext, Future }

case class RichIpAddress(ip: IpAddress, org: Option[String], country: Option[String], region: Option[String], city: Option[String],
  lat: Option[Double], lon: Option[Double], timezone: Option[String], zip: Option[String])

object RichIpAddress {
  def apply(ip: IpAddress, json: JsValue): RichIpAddress = {
    (json \ "query").asOpt[String] foreach { parsed => assert(ip.ip == parsed, s"parsed ip from json $json does not equal [$ip]/[$parsed]") }
    RichIpAddress(
      ip,
      (json \ "org").asOpt[String].orElse((json \ "isp").asOpt[String]),
      (json \ "country").asOpt[String].orElse((json \ "countryCode").asOpt[String]), (json \ "regionName").asOpt[String].orElse((json \ "region").asOpt[String]), (json \ "city").asOpt[String],
      (json \ "lat").asOpt[Double], (json \ "lon").asOpt[Double],
      (json \ "timezone").asOpt[String], (json \ "zip").asOpt[String])
  }
}

object UserIpAddressRules {
  val blacklistCompanies = Set("Digital Ocean", "AT&T Wireless", "Verizon Wireless").map(_.toLowerCase)
}

class UserIpAddressCommander @Inject() (
    db: Database,
    implicit val defaultContext: ExecutionContext,
    clock: Clock,
    httpClient: HttpClient,
    userIpAddressRepo: UserIpAddressRepo,
    userRepo: UserRepo,
    userStatisticsCommander: UserStatisticsCommander) extends Logging {

  private val ipClusterSlackChannelUrl = "https://hooks.slack.com/services/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"
  private val clusterMemoryTime = Period.weeks(10) // How long back do we look and still consider a user to be part of a cluster

  def simplifyUserAgent(userAgent: UserAgent): String = {
    val agentType = userAgent.typeName.toUpperCase()
    if (agentType.isEmpty) "NONE" else agentType
  }

  def logUser(userId: Id[User], ip: IpAddress, userAgent: UserAgent, reportNewClusters: Boolean = true): Future[Unit] = SafeFuture {
    if (ip.ip.toString.startsWith("10.")) {
      throw new IllegalArgumentException("IP Addresses of the form 10.x.x.x are internal ec2 addresses and should not be logged")
    }
    val now = clock.now()
    val agentType = simplifyUserAgent(userAgent)
    if (agentType == "NONE") {
      log.info("[IPTRACK AGENT] Could not parse an agent type out of: " + userAgent)
    }
    val model = UserIpAddress(userId = userId, ipAddress = ip, agentType = agentType)

    val cluster = db.readWrite { implicit session =>
      val currentCluster = userIpAddressRepo.getUsersFromIpAddressSince(ip, now.minus(clusterMemoryTime))
      userIpAddressRepo.saveIfNew(model)
      currentCluster.toSet
    }

    if (reportNewClusters && !cluster.contains(userId) && cluster.size >= 1) {
      log.info("[IPTRACK NOTIFY] Cluster " + cluster + " has new member " + userId)
      notifySlackChannelAboutCluster(clusterIp = ip, clusterMembers = cluster + userId, newUserId = Some(userId))
    }
  }

  def logUserByRequest[T](request: UserRequest[T]): Unit = {
    val userId = request.userId
    val userAgent = UserAgent(request.headers.get("user-agent").getOrElse(""))
    val raw_ip_string = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    val ip = IpAddress(raw_ip_string.split(",").head)
    logUser(userId, ip, userAgent)
  }

  private def formatCluster(ip: RichIpAddress, users: Seq[UserStatistics], newUserId: Option[Id[User]]): BasicSlackMessage = {
    val clusterDeclaration = Seq(
      s"Found a cluster of ${users.length} at <http://ip-api.com/$ip|$ip>",
      s"I think the company is in ${ip.region.getOrElse("")} ${ip.country.getOrElse("")} ",
      s"I think the company is '${ip.org}'"
    )

    val userDeclarations = users.map { stats =>
      val user = stats.user
      val primaryMail = user.primaryEmail.map(_.address).getOrElse("No Primary Mail")
      val userDeclaration = s"<http://admin.kifi.com/admin/user/${user.id.get}|${user.fullName}>\t$primaryMail\tjoined ${user.createdAt}\t${stats.connections} connections\t${stats.librariesCreated}/${stats.librariesFollowed} lib cr/fw\t${stats.privateKeeps}/${stats.publicKeeps} pb/pv keeps\t"
      if (newUserId.contains(user.id.get)) {
        "*" + userDeclaration + " <-- New Member in Cluster!!!*"
      } else userDeclaration
    }

    BasicSlackMessage((clusterDeclaration ++ userDeclarations).mkString("\n"))
  }

  private def heuristicsSayThisClusterIsRelevant(ipInfo: RichIpAddress): Boolean = {
    !ipInfo.org.exists(company => UserIpAddressRules.blacklistCompanies.contains(company.toLowerCase))
  }

  def notifySlackChannelAboutCluster(clusterIp: IpAddress, clusterMembers: Set[Id[User]], newUserId: Option[Id[User]] = None): Future[Unit] = SafeFuture {
    log.info("[IPTRACK NOTIFY] Notifying slack channel about " + clusterIp)
    val usersFromCluster = db.readOnlyMaster { implicit session =>
      val userIds = clusterMembers.toSeq
      userRepo.getUsers(userIds).values.toSeq.map { user =>
        userStatisticsCommander.userStatistics(user, Map.empty)
      }
    }
    val ipInfoOpt = httpClient.get(DirectUrl("http://pro.ip-api.com/json/" + clusterIp + "?key=mnU7wRVZAx6BAyP")).json.asOpt[JsObject] map { json =>
      RichIpAddress(clusterIp, json)
    }
    log.info("[IPTRACK NOTIFY] Retrieved IP geolocation info: " + ipInfoOpt)

    ipInfoOpt foreach { ipInfo =>
      if (heuristicsSayThisClusterIsRelevant(ipInfo)) {
        val msg = formatCluster(ipInfo, usersFromCluster, newUserId)
        httpClient.post(DirectUrl(ipClusterSlackChannelUrl), Json.toJson(msg))
      }
    }
  }

  def totalNumberOfLogs(): Int = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.count }
  }

  def countByUser(userId: Id[User]): Int = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.countByUser(userId) }
  }
  def getByUser(userId: Id[User], limit: Int): Seq[UserIpAddress] = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.getByUser(userId, limit) }
  }

  def getUsersByIpAddressSince(ip: IpAddress, time: DateTime): Seq[Id[User]] = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.getUsersFromIpAddressSince(ip, time) }
  }

  def kvPairsToMap[A, B](kvs: Seq[(A, B)]): Map[A, Seq[B]] = {
    kvs.groupBy(_._1).mapValues(_.map(_._2))
  }
  def findSharedIpsByUser(userId: Id[User], limit: Int): Map[IpAddress, Seq[Id[User]]] = {
    val sharedIps = db.readOnlyReplica { implicit session =>
      userIpAddressRepo.findSharedIpsByUser(userId, limit)
    }
    kvPairsToMap(sharedIps)
  }
  def findIpClustersSince(time: DateTime, limit: Int): Seq[IpAddress] = {
    db.readOnlyReplica { implicit session =>
      userIpAddressRepo.findIpClustersSince(time, limit)
    }
  }
}
