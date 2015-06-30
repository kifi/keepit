package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.UserRequest
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient, UserAgent }
import com.keepit.common.service.IpAddress
import com.keepit.model._
import org.joda.time.{ DateTime, Period }
import play.api.libs.json.{ JsObject, Json }

import scala.concurrent.{ ExecutionContext, Future }

class UserIpAddressCommander @Inject() (
    db: Database,
    implicit val defaultContext: ExecutionContext,
    httpClient: HttpClient,
    userIpAddressRepo: UserIpAddressRepo,
    userRepo: UserRepo) extends Logging {

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
    val now = DateTime.now()
    val agentType = simplifyUserAgent(userAgent)
    if (agentType == "NONE") {
      log.info("[IPTRACK AGENT] Could not parse an agent type out of: " + userAgent)
    }
    val model = UserIpAddress(userId = userId, ipAddress = ip, agentType = agentType)

    val cluster = db.readWrite { implicit session =>
      val currentCluster = userIpAddressRepo.getUsersFromIpAddressSince(ip, now.minus(clusterMemoryTime))
      userIpAddressRepo.saveIfNew(model)
      currentCluster.toSet + userId
    }

    if (reportNewClusters && cluster.size > 1) {
      log.info("[IPTRACK NOTIFY] Cluster " + cluster + " has new member " + userId)
      notifySlackChannelAboutCluster(clusterIp = ip, clusterMembers = cluster, newUserId = Some(userId))
    }
  }

  def logUserByRequest[T](request: UserRequest[T]): Unit = {
    val userId = request.userId
    val userAgent = UserAgent(request.headers.get("user-agent").getOrElse(""))
    val raw_ip_string = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    val ip = IpAddress(raw_ip_string.split(",").head)
    logUser(userId, ip, userAgent)
  }

  def formatCluster(ip: IpAddress, users: Seq[User], newUserId: Option[Id[User]], company: Option[String] = None): BasicSlackMessage = {
    val clusterDeclaration = Seq(
      Some(s"Found a cluster of ${users.length} at <http://ip-api.com/$ip|$ip>"),
      company.map("I think the company is '" + _ + "'")
    ).flatten

    val userDeclarations = users.map { u =>
      val userDeclaration = s"<http://admin.kifi.com/admin/user/${u.id.get}|${u.fullName}>"
      if (newUserId.contains(u.id.get)) {
        "*" + userDeclaration + " <-- New Member!!!*"
      } else userDeclaration
    }

    BasicSlackMessage((clusterDeclaration ++ userDeclarations).mkString("\n"))
  }

  def heuristicsSayThisClusterIsRelevant(ipInfo: Option[JsObject]): Boolean = {
    val companyOpt = ipInfo flatMap { obj => (obj \ "org").asOpt[String] }
    val blacklistCompanies = Set.empty[String]

    !companyOpt.exists(company => blacklistCompanies.contains(company))
  }
  def notifySlackChannelAboutCluster(clusterIp: IpAddress, clusterMembers: Set[Id[User]], newUserId: Option[Id[User]] = None): Future[Unit] = SafeFuture {
    log.info("[IPTRACK NOTIFY] Notifying slack channel about " + clusterIp)
    val usersFromCluster = db.readOnlyMaster { implicit session =>
      userRepo.getUsers(clusterMembers.toSeq).values.toSeq
    }
    val ipInfo = httpClient.get(DirectUrl("http://pro.ip-api.com/json/" + clusterIp + "?key=mnU7wRVZAx6BAyP")).json.asOpt[JsObject]
    log.info("[IPTRACK NOTIFY] Retrieved IP geolocation info: " + ipInfo)
    val companyOpt = ipInfo.flatMap(info => (info \ "org").asOpt[String])

    if (heuristicsSayThisClusterIsRelevant(ipInfo)) {
      val msg = formatCluster(clusterIp, usersFromCluster, newUserId, companyOpt)
      httpClient.post(DirectUrl(ipClusterSlackChannelUrl), Json.toJson(msg))
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
